package com.moral;

import com.moral.ui.MainFrame;
import com.moral.util.PlatformUtil;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * 程序入口：初始化跨平台 UI 环境并打开主窗口。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(new FlatLightLaf());
        } catch (Exception ignore) {
            // 主题设置失败时使用系统默认外观
        }
        PlatformUtil.init();
        SwingUtilities.invokeLater(() -> {
            try {
                new MainFrame().setVisible(true);
            } catch (Throwable error) {
                error.printStackTrace();
                JOptionPane.showMessageDialog(null, "界面初始化失败：" + error.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
