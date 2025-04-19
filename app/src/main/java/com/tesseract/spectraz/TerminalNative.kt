package com.tesseract.spectraz

object TerminalNative {
    init {
        System.loadLibrary("spectraz")
    }

    external fun startShell(): Boolean
    external fun sendToShell(cmd: String)
    external fun readFromShell(): String
}
