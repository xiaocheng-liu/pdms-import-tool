package com.moral.ui;

import javax.swing.BorderFactory;
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
import java.awt.FlowLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 日志区：分级彩色输出，自动滚动，清空按钮。
 */
public class LogPanel extends JPanel {

    /** 日志级别（配色按浅色背景选，保证白底可读） */
    public enum Level {
        INFO(new Color(0x1F2937)),
        SUCCESS(new Color(0x16A34A)),
        WARN(new Color(0xD97706)),
        ERROR(new Color(0xDC2626));

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
        setBorder(BorderFactory.createTitledBorder("运行日志"));
        setLayout(new BorderLayout(4, 4));
        textPane.setEditable(false);
        add(new JScrollPane(textPane), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton clearButton = new JButton("清空日志");
        clearButton.addActionListener(event -> clear());
        buttonPanel.add(clearButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    public void append(String message, Level level) {
        String line = "[" + LocalTime.now().format(TIME_FORMAT) + "] " + message + "\n";
        Runnable task = () -> {
            StyledDocument document = textPane.getStyledDocument();
            SimpleAttributeSet attributes = new SimpleAttributeSet();
            StyleConstants.setForeground(attributes, level.getColor());
            try {
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
