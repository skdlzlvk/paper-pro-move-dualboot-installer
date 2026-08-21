QT += core gui
CONFIG += console c++17
CONFIG -= app_bundle

TARGET = rm-epd-bridge
SOURCES += rm-epd-bridge.cpp

INCLUDEPATH += $$PWD/third_party/oxide

LIBS += -L$$[QT_SYSROOT]/usr/lib/plugins/scenegraph -lqsgepaper -ldl
QMAKE_RPATHDIR += /usr/lib/plugins/scenegraph
