package com.moral;

import com.moral.ui.MainFrame;
import com.moral.util.AppIcons;
import com.moral.util.PlatformUtil;
import com.moral.util.UiTheme;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * 程序入口：初始化跨平台 UI 环境并打开主窗口。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        try {
            // setup() 会完整初始化 UI defaults，之后才能覆盖主题键
            FlatLightLaf.setup();
            UiTheme.install();
        } catch (Exception ignore) {
            // 主题设置失败时使用系统默认外观
        }
        PlatformUtil.init();
        AppIcons.applyToTaskbar();
        SwingUtilities.invokeLater(() -> {
            try {
                MainFrame frame = new MainFrame();
                AppIcons.applyTo(frame);
                frame.setVisible(true);
            } catch (Throwable error) {
                error.printStackTrace();
                JOptionPane.showMessageDialog(null, "界面初始化失败：" + error.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
