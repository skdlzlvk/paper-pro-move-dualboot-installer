QT += core gui
CONFIG += console c++17
CONFIG -= app_bundle

TARGET = rm-boot-selector
SOURCES += rm-boot-selector.cpp

INCLUDEPATH += $$PWD/../display/third_party/oxide

LIBS += -L$$[QT_SYSROOT]/usr/lib/plugins/scenegraph -lqsgepaper -ldl
QMAKE_RPATHDIR += /usr/lib/plugins/scenegraph
