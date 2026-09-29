package com.moral.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建表语句预览与编辑弹窗（防止误建表）。
 */
public class DdlPreviewDialog extends JDialog {

    private final Map<String, String> ddlMap;
    private final JComboBox<String> tableCombo = new JComboBox<>();
    private final JTextArea ddlArea = new JTextArea();
    private boolean confirmed;

    public DdlPreviewDialog(Frame owner, Map<String, String> ddlMap) {
        super(owner, "建表语句预览（可编辑）", true);
        this.ddlMap = new LinkedHashMap<>(ddlMap);
        setLayout(new BorderLayout(8, 8));

        List<String> names = new ArrayList<>(this.ddlMap.keySet());
        for (String name : names) {
            tableCombo.addItem(name);
        }

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        topPanel.add(new JLabel("目标库中缺失的表："));
        topPanel.add(tableCombo);
        JButton applyButton = new JButton("保存修改");
        topPanel.add(applyButton);

        ddlArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
        ddlArea.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton okButton = new JButton("确认执行并导入");
        JButton cancelButton = new JButton("取消");
        bottomPanel.add(okButton);
        bottomPanel.add(cancelButton);

        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(ddlArea), BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        tableCombo.addActionListener(event -> showSelected());
        applyButton.addActionListener(event -> {
            String name = (String) tableCombo.getSelectedItem();
            if (name != null) {
                this.ddlMap.put(name, ddlArea.getText());
            }
        });
        okButton.addActionListener(event -> {
            String name = (String) tableCombo.getSelectedItem();
            if (name != null) {
                this.ddlMap.put(name, ddlArea.getText());
            }
            confirmed = true;
            dispose();
        });
        cancelButton.addActionListener(event -> dispose());

        showSelected();
        setSize(new Dimension(760, 520));
        setLocationRelativeTo(owner);
    }

    private void showSelected() {
        String name = (String) tableCombo.getSelectedItem();
        if (name != null) {
            ddlArea.setText(ddlMap.get(name));
            ddlArea.setCaretPosition(0);
        }
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public Map<String, String> getDdlMap() {
        return ddlMap;
    }
}
