#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <pty.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>

int shell_pid = -1;
int master_fd = -1;

extern "C" {

// Start a shell session
JNIEXPORT jboolean JNICALL
Java_com_tesseract_spectraz_TerminalNative_startShell(JNIEnv *env, jobject) {
    struct winsize win = {24, 80, 0, 0};
    pid_t pid = forkpty(&master_fd, nullptr, nullptr, &win);
    if (pid < 0) return JNI_FALSE;

    if (pid == 0) {
        // We're in the child process
        execlp("su", "su", nullptr);  // Use "sh" if root isn't required
        _exit(1);  // If shell fails
    }

    shell_pid = pid;
    return JNI_TRUE;
}

// Send a command to shell
JNIEXPORT void JNICALL
Java_com_tesseract_spectraz_TerminalNative_sendToShell(JNIEnv *env, jobject, jstring cmd) {
    const char *nativeCmd = env->GetStringUTFChars(cmd, nullptr);
    write(master_fd, nativeCmd, strlen(nativeCmd));
    write(master_fd, "\n", 1);
    env->ReleaseStringUTFChars(cmd, nativeCmd);
}

// Read output from shell
JNIEXPORT jstring JNICALL
Java_com_tesseract_spectraz_TerminalNative_readFromShell(JNIEnv *env, jobject) {
    char buffer[1024];
    int len = read(master_fd, buffer, sizeof(buffer) - 1);
    if (len > 0) {
        buffer[len] = '\0';
        return env->NewStringUTF(buffer);
    }
    return env->NewStringUTF("");
}

}
