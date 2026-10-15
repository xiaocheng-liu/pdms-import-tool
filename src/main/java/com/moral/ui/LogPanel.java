package com.moral.ui;

import com.moral.util.UiTheme;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 日志区：分级彩色输出，自动滚动，清空按钮。
 */
public class LogPanel extends JPanel {

    /** 日志级别（配色按浅色背景选，保证白底可读） */
    public enum Level {
        INFO(UiTheme.TEXT_PRIMARY),
        SUCCESS(UiTheme.STATUS_SUCCESS),
        WARN(UiTheme.STATUS_WARN),
        ERROR(UiTheme.STATUS_ERROR);

        private final Color color;

        Level(Color color) {
            this.color = color;
        }

        public Color getColor() {
            return color;
        }
    }

    private static final int MAX_CHARS = 200000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextPane textPane = new JTextPane();

    public LogPanel() {
        setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(UiTheme.BORDER),
                javax.swing.BorderFactory.createEmptyBorder(5, 6, 6, 6)));
        setLayout(new BorderLayout(4, 4));
        textPane.setEditable(false);
        textPane.setBackground(UiTheme.CARD_BG);
        textPane.setFont(UiTheme.monoFont());
        add(new JScrollPane(textPane), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new BorderLayout(8, 0));
        javax.swing.JLabel title = new javax.swing.JLabel("运行日志");
        title.setFont(UiTheme.boldFont());
        title.setForeground(UiTheme.TEXT_PRIMARY);
        buttonPanel.add(title, BorderLayout.WEST);
        buttonPanel.setOpaque(false);
        JButton clearButton = new JButton("清空日志");
        clearButton.addActionListener(event -> clear());
        buttonPanel.add(clearButton, BorderLayout.EAST);
        add(buttonPanel, BorderLayout.NORTH);
    }

    public void append(String message, Level level) {
        // 时间戳弱化显示，正文按级别着色
        String time = "[" + LocalTime.now().format(TIME_FORMAT) + "] ";
        String line = message + "\n";
        Runnable task = () -> {
            StyledDocument document = textPane.getStyledDocument();
            SimpleAttributeSet timeAttributes = new SimpleAttributeSet();
            StyleConstants.setForeground(timeAttributes, UiTheme.TEXT_SECONDARY);
            SimpleAttributeSet attributes = new SimpleAttributeSet();
            StyleConstants.setForeground(attributes, level.getColor());
            try {
                document.insertString(document.getLength(), time, timeAttributes);
                document.insertString(document.getLength(), line, attributes);
                if (document.getLength() > MAX_CHARS) {
                    document.remove(0, document.getLength() - MAX_CHARS);
                }
                textPane.setCaretPosition(document.getLength());
            } catch (BadLocationException ignore) {
                // 日志写入失败忽略
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    public void clear() {
        Runnable task = () -> textPane.setText("");
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }
}
