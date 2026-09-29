package com.moral.util;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;

/**
 * Swing 通用工具：EDT 调度与提示框封装。
 */
public final class SwingUtil {

    private SwingUtil() {
    }

    /** 在 EDT 线程执行 */
    public static void invoke(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    public static void showError(Component parent, String message) {
        invoke(() -> JOptionPane.showMessageDialog(parent, message, "错误", JOptionPane.ERROR_MESSAGE));
    }

    public static void showInfo(Component parent, String message) {
        invoke(() -> JOptionPane.showMessageDialog(parent, message, "提示", JOptionPane.INFORMATION_MESSAGE));
    }

    public static boolean confirm(Component parent, String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            return JOptionPane.showConfirmDialog(parent, message, "确认", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
        }
        final boolean[] result = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(() ->
                    result[0] = JOptionPane.showConfirmDialog(parent, message, "确认", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION);
        } catch (Exception ignore) {
            result[0] = false;
        }
        return result[0];
    }

    /** 设置按钮/面板等的可用状态（EDT 安全） */
    public static void setEnabled(JComponent component, boolean enabled) {
        invoke(() -> component.setEnabled(enabled));
    }
}
