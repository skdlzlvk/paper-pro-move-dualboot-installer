package com.android.launcher3.paper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Verifies and stages project Android updates without touching a partition. */
final class OtaUpdateClient {
    static final int UPDATER_VERSION = 1;
    private static final int MAX_ENVELOPE_BYTES = 128 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_ARTIFACTS = 4;
    private static final long MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final long CLOCK_SKEW_MILLIS = 5L * 60L * 1000L;

    interface Progress {
        void update(String name, long received, long total);
    }

    static final class Configuration {
        final String feedBaseUrl;
        final String trustedKeyId;
        final String publicKeySpki;
        final String currentReleaseId;
        final String installedStockVersion;
        final String channel;
        final String physicalModel;

        Configuration(String feedBaseUrl, String trustedKeyId,
                String publicKeySpki, String currentReleaseId,
                String installedStockVersion, String channel,
                String physicalModel) {
            this.feedBaseUrl = feedBaseUrl.trim();
            this.trustedKeyId = trustedKeyId.trim();
            this.publicKeySpki = publicKeySpki.trim();
            this.currentReleaseId = currentReleaseId.trim();
            this.installedStockVersion = installedStockVersion.trim();
            this.channel = channel.trim();
            this.physicalModel = physicalModel.trim();
        }

        boolean isConfigured() {
            return feedBaseUrl.startsWith("https://")
                    && !trustedKeyId.isEmpty()
                    && !publicKeySpki.isEmpty()
                    && !currentReleaseId.isEmpty()
                    && !installedStockVersion.isEmpty();
        }

        URL feedUrl() throws Exception {
            String base = feedBaseUrl.endsWith("/")
                    ? feedBaseUrl : feedBaseUrl + "/";
            return requireHttps(new URL(base + channel + ".json"));
        }
    }

    static final class Artifact {
        final String name;
        final URL url;
        final String sha256;
        final long sizeBytes;
        final String purpose;

        Artifact(String name, URL url, String sha256,
                long sizeBytes, String purpose) {
            this.name = name;
            this.url = url;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.purpose = purpose;
        }
    }

    static final class Release {
        final String releaseId;
        final String toReleaseId;
        final String channel;
        final int minimumBatteryPercent;
        final long totalBytes;
        final List<Artifact> artifacts;
        final String envelopeText;
        final String payloadText;

        Release(String releaseId, String toReleaseId, String channel,
                int minimumBatteryPercent, long totalBytes,
                List<Artifact> artifacts, String envelopeText,
                String payloadText) {
            this.releaseId = releaseId;
            this.toReleaseId = toReleaseId;
            this.channel = channel;
            this.minimumBatteryPercent = minimumBatteryPercent;
            this.totalBytes = totalBytes;
            this.artifacts = artifacts;
            this.envelopeText = envelopeText;
            this.payloadText = payloadText;
        }
    }

    private OtaUpdateClient() {
    }

    static Release check(Configuration configuration) throws Exception {
        if (!configuration.isConfigured()) {
            throw new IOException("update service is not configured");
        }
        String envelope = new String(
                readSmall(configuration.feedUrl(), MAX_ENVELOPE_BYTES),
                StandardCharsets.UTF_8);
        return verifyEnvelope(envelope, configuration,
                System.currentTimeMillis());
    }

    static Release verifyEnvelope(String envelopeText,
            Configuration configuration, long nowMillis) throws Exception {
        JSONObject envelope = new JSONObject(envelopeText);
        requireExactKeys(envelope, "schemaVersion", "algorithm", "keyId",
                "signedPayload", "signature");
        require(envelope.getInt("schemaVersion") == 1,
                "unsupported envelope version");
        require("ECDSA_P256_SHA256".equals(envelope.getString("algorithm")),
                "unsupported signature algorithm");
        require(configuration.trustedKeyId.equals(envelope.getString("keyId")),
                "untrusted update key");

        byte[] payloadBytes = decodeBase64Url(
                envelope.getString("signedPayload"));
        byte[] signatureBytes = decodeBase64Url(
                envelope.getString("signature"));
        require(payloadBytes.length > 0
                        && payloadBytes.length <= MAX_ENVELOPE_BYTES,
                "invalid signed payload size");
        PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(decodePublicKey(
                        configuration.publicKeySpki)));
        require(publicKey instanceof ECPublicKey
                        && ((ECPublicKey) publicKey).getParams()
                                .getCurve().getField().getFieldSize() == 256,
                "trusted update key is not P-256");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(payloadBytes);
        require(verifier.verify(signatureBytes), "invalid update signature");

        String payloadText = new String(payloadBytes, StandardCharsets.UTF_8);
        JSONObject payload = new JSONObject(payloadText);
        requireExactKeys(payload, "payloadVersion", "releaseId", "channel",
                "createdAt", "expiresAt", "installable",
                "minimumUpdaterVersion", "device", "android", "target",
                "artifacts");
        require(payload.getInt("payloadVersion") == 1,
                "unsupported payload version");
        require(payload.getBoolean("installable"),
                "release is not installable");
        require(payload.getInt("minimumUpdaterVersion") <= UPDATER_VERSION,
                "updater is too old");
        require(configuration.channel.equals(payload.getString("channel")),
                "release channel mismatch");
        long createdAt = Instant.parse(payload.getString("createdAt"))
                .toEpochMilli();
        long expiresAt = Instant.parse(payload.getString("expiresAt"))
                .toEpochMilli();
        require(createdAt <= nowMillis + CLOCK_SKEW_MILLIS,
                "release timestamp is in the future");
        require(expiresAt > nowMillis, "release metadata has expired");

        JSONObject device = payload.getJSONObject("device");
        requireExactKeys(device, "deviceTreeModel",
                "supportedStockVersions");
        require("reMarkable Chiappa".equals(
                        device.getString("deviceTreeModel")),
                "manifest targets a different device");
        require("reMarkable Chiappa".equals(configuration.physicalModel),
                "physical device is not Paper Pro Move");
        JSONArray supportedStockVersions =
                device.getJSONArray("supportedStockVersions");
        boolean stockAccepted = false;
        for (int index = 0; index < supportedStockVersions.length(); index++) {
            stockAccepted |= configuration.installedStockVersion.equals(
                    supportedStockVersions.getString(index));
        }
        require(stockAccepted, "installed stock baseline is not supported");

        JSONObject android = payload.getJSONObject("android");
        requireExactKeys(android, "fromReleaseIds", "toReleaseId", "sdk");
        require(android.getInt("sdk") == 36, "Android SDK mismatch");
        JSONArray from = android.getJSONArray("fromReleaseIds");
        boolean currentAccepted = false;
        for (int index = 0; index < from.length(); index++) {
            currentAccepted |= configuration.currentReleaseId.equals(
                    from.getString(index));
        }
        require(currentAccepted, "current release is not updateable");
        String toReleaseId = android.getString("toReleaseId");
        require(!configuration.currentReleaseId.equals(toReleaseId),
                "release is already installed");

        JSONObject target = payload.getJSONObject("target");
        requireExactKeys(target, "stockSlot", "androidSlot", "partition",
                "sizeBytes", "minimumBatteryPercent");
        require("a".equals(target.getString("stockSlot"))
                        && "b".equals(target.getString("androidSlot"))
                        && "/dev/mmcblk0p3".equals(
                                target.getString("partition"))
                        && target.getLong("sizeBytes") == 4294967296L,
                "unsafe slot or partition target");
        int minimumBattery = target.getInt("minimumBatteryPercent");
        require(minimumBattery >= 60 && minimumBattery <= 100,
                "unsafe battery threshold");

        JSONArray artifactJson = payload.getJSONArray("artifacts");
        require(artifactJson.length() > 0
                        && artifactJson.length() <= MAX_ARTIFACTS,
                "invalid artifact count");
        List<Artifact> artifacts = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long total = 0L;
        for (int index = 0; index < artifactJson.length(); index++) {
            JSONObject item = artifactJson.getJSONObject(index);
            requireExactKeys(item, "name", "url", "sha256", "sizeBytes",
                    "purpose");
            String name = item.getString("name");
            require(name.matches("[A-Za-z0-9._-]+")
                            && !name.equals(".") && !name.equals(".."),
                    "unsafe artifact name");
            require(names.add(name), "duplicate artifact name");
            String purpose = item.getString("purpose");
            require("android-root-image".equals(purpose)
                            || "paper-home-apk".equals(purpose)
                            || "host-integration".equals(purpose),
                    "unsupported artifact purpose");
            long size = item.getLong("sizeBytes");
            require(size > 0 && size <= MAX_TOTAL_BYTES,
                    "invalid artifact size");
            total = Math.addExact(total, size);
            require(total <= MAX_TOTAL_BYTES, "update is too large");
            String hash = item.getString("sha256");
            require(hash.matches("[a-f0-9]{64}"),
                    "invalid artifact digest");
            artifacts.add(new Artifact(name,
                    requireHttps(new URL(item.getString("url"))),
                    hash, size, purpose));
        }
        String releaseId = payload.getString("releaseId");
        require(releaseId.matches("[a-z0-9][a-z0-9._-]{2,63}"),
                "invalid release id");
        require(releaseId.equals(toReleaseId), "release id mismatch");
        return new Release(releaseId, toReleaseId,
                payload.getString("channel"), minimumBattery, total,
                artifacts, envelopeText, payloadText);
    }

    static File stage(Context context, Release release,
            Progress progress) throws Exception {
        verifyStagingFilesystem(context);
        File root = new File(context.getFilesDir(), "ota-staging");
        requireDirectory(root);
        File temporary = new File(root, release.releaseId + ".downloading");
        deleteTree(temporary);
        requireDirectory(temporary);
        require(temporary.getUsableSpace() >= release.totalBytes
                        + 256L * 1024L * 1024L,
                "not enough free storage");
        try {
            writeAndSync(new File(temporary, "envelope.json"),
                    release.envelopeText.getBytes(StandardCharsets.UTF_8));
            writeAndSync(new File(temporary, "payload.json"),
                    release.payloadText.getBytes(StandardCharsets.UTF_8));
            for (Artifact artifact : release.artifacts) {
                File part = new File(temporary, artifact.name + ".part");
                downloadArtifact(artifact, part, progress);
                File ready = new File(temporary, artifact.name);
                require(part.renameTo(ready), "cannot finalize artifact");
            }
            writeAndSync(new File(temporary, ".ready"),
                    (release.releaseId + "\n").getBytes(
                            StandardCharsets.US_ASCII));
            File readyDirectory = new File(root, release.releaseId);
            deleteTree(readyDirectory);
            require(temporary.renameTo(readyDirectory),
                    "cannot publish staged update");
            writeAtomic(root, "current-ready",
                    (release.releaseId + "\n").getBytes(
                            StandardCharsets.US_ASCII));
            return readyDirectory;
        } catch (Exception failure) {
            deleteTree(temporary);
            throw failure;
        }
    }

    private static void downloadArtifact(Artifact artifact, File part,
            Progress progress) throws Exception {
        HttpURLConnection connection = open(artifact.url);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long received = 0L;
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(part, false)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("download cancelled");
                }
                if (count == 0) {
                    continue;
                }
                received += count;
                require(received <= artifact.sizeBytes,
                        "artifact exceeds signed size");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                progress.update(artifact.name, received, artifact.sizeBytes);
            }
            output.flush();
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
        require(received == artifact.sizeBytes, "artifact size mismatch");
        require(hex(digest.digest()).equals(artifact.sha256),
                "artifact digest mismatch");
    }

    private static byte[] readSmall(URL url, int maximum) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                require(output.size() + count <= maximum,
                        "update metadata is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(URL initial) throws Exception {
        URL current = requireHttps(initial);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection =
                    (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent",
                    "PaperHome-OTA/" + UPDATER_VERSION);
            int code = connection.getResponseCode();
            if (code >= 300 && code <= 399) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                require(location != null && redirect < MAX_REDIRECTS,
                        "unsafe update redirect");
                current = requireHttps(new URL(current, location));
                continue;
            }
            require(code == HttpURLConnection.HTTP_OK,
                    "update server HTTP " + code);
            return connection;
        }
        throw new IOException("too many redirects");
    }

    private static URL requireHttps(URL url) throws Exception {
        URI uri = url.toURI();
        require("https".equalsIgnoreCase(uri.getScheme())
                        && uri.getHost() != null
                        && !uri.getHost().isEmpty()
                        && uri.getUserInfo() == null
                        && uri.getFragment() == null,
                "update URL must be plain HTTPS");
        return url;
    }

    private static byte[] decodePublicKey(String value) {
        String compact = value.replaceAll("\\s", "");
        return Base64.getDecoder().decode(compact);
    }

    private static byte[] decodeBase64Url(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static void requireExactKeys(JSONObject object, String... keys)
            throws JSONException, IOException {
        Set<String> expected = new HashSet<>();
        for (String key : keys) {
            expected.add(key);
        }
        Set<String> actual = new HashSet<>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            actual.add(iterator.next());
        }
        require(expected.equals(actual), "unexpected metadata fields");
    }

    private static void requireDirectory(File directory) throws IOException {
        require((directory.isDirectory() || directory.mkdirs())
                        && !directory.isFile(),
                "cannot create staging directory");
    }

    private static void writeAndSync(File file, byte[] bytes)
            throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void writeAtomic(File directory, String name, byte[] bytes)
            throws IOException {
        require(name.matches("[A-Za-z0-9._-]+"), "unsafe marker name");
        File temporary = new File(directory, "." + name + ".part");
        File destination = new File(directory, name);
        require(!temporary.exists() || temporary.delete(),
                "cannot clear stale marker");
        writeAndSync(temporary, bytes);
        require(!destination.exists() || destination.delete(),
                "cannot replace ready marker");
        require(temporary.renameTo(destination),
                "cannot publish ready marker");
    }

    private static void verifyStagingFilesystem(Context context)
            throws IOException {
        String filesPath = context.getFilesDir().getCanonicalPath();
        String bestMount = "";
        String bestSource = "";
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/self/mountinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(" ");
                int separator = -1;
                for (int index = 6; index < fields.length; index++) {
                    if ("-".equals(fields[index])) {
                        separator = index;
                        break;
                    }
                }
                if (separator < 0 || separator + 2 >= fields.length
                        || fields.length <= 4) {
                    continue;
                }
                String mount = unescapeMount(fields[4]);
                if ((filesPath.equals(mount)
                        || filesPath.startsWith(mount + "/"))
                        && mount.length() > bestMount.length()) {
                    bestMount = mount;
                    bestSource = unescapeMount(fields[separator + 2]);
                }
            }
        }
        require(!bestMount.isEmpty(), "cannot identify staging filesystem");
        require(!"/dev/mmcblk0p3".equals(bestSource)
                        && (bestSource.startsWith("/dev/dm-")
                                || bestSource.startsWith("/dev/mapper/")),
                "staging is not on separate encrypted data storage");
    }

    private static String unescapeMount(String value) {
        return value.replace("\\040", " ")
                .replace("\\011", "\t")
                .replace("\\012", "\n")
                .replace("\\134", "\\");
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        File canonical = file.getCanonicalFile();
        require(canonical.getName().endsWith(".downloading")
                        || canonical.getParentFile().getName()
                                .equals("ota-staging"),
                "refusing unsafe staging cleanup");
        File[] children = canonical.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteTree(child);
                } else {
                    require(child.delete(), "cannot delete stale artifact");
                }
            }
        }
        require(canonical.delete(), "cannot delete stale staging path");
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return result.toString();
    }

    private static void require(boolean condition, String message)
            throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }
}
