package com.moral;

import com.moral.ui.MainFrame;
import com.moral.util.AppIcons;
import com.moral.util.PlatformUtil;
import com.moral.util.UiTheme;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 程序入口：初始化跨平台 UI 环境并打开主窗口。
 */
public final class Launcher {

    /** JDK 内置日志：无第三方依赖，堆栈可按需重定向到文件或关闭 */
    private static final Logger LOG = Logger.getLogger(Launcher.class.getName());

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
                // 用日志而非 printStackTrace 输出堆栈：带级别与时间戳，且可配置输出目标
                LOG.log(Level.SEVERE, "界面初始化失败，程序即将退出", error);
                JOptionPane.showMessageDialog(null, "界面初始化失败：" + error.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
