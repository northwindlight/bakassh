/*
 * bakassh —— 配合 SelectionArea.copyFrom 使用。
 * 原版 ConnectBot 直接读它自己的 VT 缓冲（de.mud.terminal.VDUBuffer）；
 * bakassh 的终端是 libvterm（JNI），所以抽成这个接口，由调用方按行列喂字符。
 */
package org.connectbot.bean;

public interface LineSource {
    /** 该行第 x 列的可打印字符（宽字符的尾随占位格给空格）。 */
    char charAt(int x, int y);
}
