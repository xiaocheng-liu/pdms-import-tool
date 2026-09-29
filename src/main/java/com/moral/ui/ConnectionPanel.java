package com.moral.ui;

import com.moral.db.ConnectionFactory;
import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;
import com.moral.util.SwingUtil;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * 数据库连接配置卡片。
 */
public class ConnectionPanel extends JPanel {

    private final JComboBox<DbType> typeCombo = new JComboBox<>(DbType.values());
    private final JTextField hostField = new JTextField("127.0.0.1");
    private final JTextField portField = new JTextField(String.valueOf(DbType.ORACLE.getDefaultPort()));
    private final JTextField databaseField = new JTextField();
    private final JTextField userField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JTextField schemaField = new JTextField("KBE");
    private final JButton testButton = new JButton("测试连接");
    private final JButton saveButton = new JButton("保存配置");
    private final JLabel statusLabel = new JLabel("未测试");
    private final JLabel databaseLabel = new JLabel("服务名/SID");

    public ConnectionPanel() {
        setBorder(BorderFactory.createTitledBorder("数据库连接"));
        setLayout(new GridBagLayout());

        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(4, 8, 4, 4);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(4, 2, 4, 8);

        int row = 0;
        addRow(labelConstraints, fieldConstraints, row++, "类型", typeCombo);
        addRow(labelConstraints, fieldConstraints, row++, "主机", hostField);
        addRow(labelConstraints, fieldConstraints, row++, "端口", portField);
        addRow(labelConstraints, fieldConstraints, row++, null, databaseLabel, databaseField);
        addRow(labelConstraints, fieldConstraints, row++, "用户名", userField);
        addRow(labelConstraints, fieldConstraints, row++, "密码", passwordField);
        addRow(labelConstraints, fieldConstraints, row++, "Schema", schemaField);

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(testButton);
        buttonPanel.add(saveButton);

        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.gridx = 0;
        buttonConstraints.gridy = row++;
        buttonConstraints.gridwidth = 2;
        buttonConstraints.insets = new Insets(6, 8, 2, 8);
        buttonConstraints.anchor = GridBagConstraints.WEST;
        add(buttonPanel, buttonConstraints);

        GridBagConstraints statusConstraints = new GridBagConstraints();
        statusConstraints.gridx = 0;
        statusConstraints.gridy = row;
        statusConstraints.gridwidth = 2;
        statusConstraints.insets = new Insets(0, 8, 6, 8);
        statusConstraints.anchor = GridBagConstraints.WEST;
        add(statusLabel, statusConstraints);

        typeCombo.addActionListener(event -> onTypeChanged());
        testButton.addActionListener(event -> testConnection());
    }

    private void addRow(GridBagConstraints labelConstraints, GridBagConstraints fieldConstraints,
                        int row, String labelText, java.awt.Component field) {
        addRow(labelConstraints, fieldConstraints, row, labelText, new JLabel(labelText), field);
    }

    private void addRow(GridBagConstraints labelConstraints, GridBagConstraints fieldConstraints,
                        int row, String labelText, JLabel label, java.awt.Component field) {
        labelConstraints.gridy = row;
        add(label, labelConstraints);
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1;
        add(field, fieldConstraints);
    }

    private void onTypeChanged() {
        DbType type = (DbType) typeCombo.getSelectedItem();
        if (type == null) {
            return;
        }
        databaseLabel.setText(type.getDatabaseLabel());
        String currentPort = portField.getText().trim();
        if (currentPort.isEmpty() || isDefaultPort(currentPort)) {
            portField.setText(String.valueOf(type.getDefaultPort()));
        }
    }

    private boolean isDefaultPort(String port) {
        for (DbType type : DbType.values()) {
            if (String.valueOf(type.getDefaultPort()).equals(port)) {
                return true;
            }
        }
        return false;
    }

    /** 从界面收集连接配置 */
    public ConnectionConfig buildConfig() {
        ConnectionConfig config = new ConnectionConfig();
        config.setDbType((DbType) typeCombo.getSelectedItem());
        config.setHost(hostField.getText().trim());
        config.setPort(parsePort(portField.getText()));
        config.setDatabase(databaseField.getText().trim());
        config.setUser(userField.getText().trim());
        config.setPassword(new String(passwordField.getPassword()));
        config.setSchema(schemaField.getText().trim());
        return config;
    }

    /** 把配置回填到界面 */
    public void applyConfig(ConnectionConfig config) {
        typeCombo.setSelectedItem(config.getDbType());
        hostField.setText(config.getHost());
        portField.setText(String.valueOf(config.getPort()));
        databaseField.setText(config.getDatabase());
        userField.setText(config.getUser());
        passwordField.setText(config.getPassword());
        schemaField.setText(config.getSchema());
        databaseLabel.setText(config.getDbType().getDatabaseLabel());
    }

    private int parsePort(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    private void testConnection() {
        ConnectionConfig config = buildConfig();
        String error = config.validate();
        if (error != null) {
            setStatus(error, false);
            return;
        }
        testButton.setEnabled(false);
        setStatus("正在连接...", true);
        new Thread(() -> {
            try {
                String message = ConnectionFactory.test(config);
                SwingUtil.invoke(() -> setStatus(message, true));
            } catch (Exception e) {
                String message = "连接失败：" + e.getMessage();
                SwingUtil.invoke(() -> setStatus(message, false));
            } finally {
                SwingUtil.invoke(() -> testButton.setEnabled(true));
            }
        }, "test-connection").start();
    }

    public void setStatus(String text, boolean ok) {
        statusLabel.setText(text);
        statusLabel.setToolTipText(text);
        // 成功绿、失败红，便于快速识别连接状态
        statusLabel.setForeground(ok ? new java.awt.Color(0x22C55E) : new java.awt.Color(0xEF4444));
    }

    public JButton getSaveButton() {
        return saveButton;
    }
}
