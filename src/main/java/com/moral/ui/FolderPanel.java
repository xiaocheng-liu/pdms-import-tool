package com.moral.ui;

import com.moral.util.FileSizeUtil;
import com.moral.util.PlatformUtil;
import com.moral.util.UiTheme;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.File;

/**
 * 数据源文件夹选择卡片。
 */
public class FolderPanel extends JPanel {

    private final JTextField dirField = new JTextField();
    private final JButton chooseButton = new JButton("选择目录");
    private final JButton scanButton = new JButton("扫描 CSV");
    private final JLabel statsLabel = new JLabel("尚未扫描");

    public FolderPanel() {
        setBorder(UiTheme.cardBorder("CSV 目录"));
        setLayout(new BorderLayout(6, 2));

        dirField.setColumns(12);
        dirField.setText(PlatformUtil.defaultCsvDir());
        dirField.setToolTipText(dirField.getText());
        dirField.setMinimumSize(new Dimension(120, dirField.getPreferredSize().height));

        JPanel topPanel = new JPanel(new BorderLayout(8, 0));
        topPanel.setOpaque(false);
        topPanel.add(dirField, BorderLayout.CENTER);
        // 按钮等宽排列，避免窄窗口下换行或挤压导致按钮不可见
        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 6, 0));
        buttonPanel.setOpaque(false);
        // 扫描是本卡片的主操作，强调显示
        UiTheme.primary(scanButton);
        buttonPanel.add(chooseButton);
        buttonPanel.add(scanButton);
        int buttonHeight = Math.max(28, chooseButton.getPreferredSize().height);
        buttonPanel.setPreferredSize(new Dimension(180, buttonHeight));
        topPanel.add(buttonPanel, BorderLayout.EAST);

        statsLabel.setText("文件名对应表名，选择目录后扫描。");
        statsLabel.setToolTipText("选择存放 CSV 的目录后点击「扫描 CSV」；CSV 文件名对应目标表名");
        UiTheme.muted(statsLabel);

        add(topPanel, BorderLayout.NORTH);
        add(statsLabel, BorderLayout.SOUTH);

        chooseButton.addActionListener(event -> chooseDirectory());
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser(dirField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择 CSV 所在文件夹");
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            dirField.setText(selected.getAbsolutePath());
            dirField.setToolTipText(selected.getAbsolutePath());
        }
    }

    public String getDirectory() {
        return dirField.getText().trim();
    }

    public void setDirectory(String directory) {
        if (directory == null || directory.trim().isEmpty()) {
            return;
        }
        dirField.setText(directory);
        dirField.setToolTipText(directory);
    }

    public JButton getScanButton() {
        return scanButton;
    }

    public void setStats(int fileCount, long totalBytes, int selectedCount) {
        statsLabel.setText(fileCount + " 个 CSV · " + FileSizeUtil.formatSize(totalBytes)
                + " · 已选 " + selectedCount + " 个");
    }

    public void setStats(String text) {
        statsLabel.setText(text);
    }
}
