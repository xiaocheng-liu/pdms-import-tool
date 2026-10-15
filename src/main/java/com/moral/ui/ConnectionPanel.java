package com.moral.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.moral.db.ConnectionFactory;
import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;
import com.moral.util.PasswordVisibilityIcon;
import com.moral.util.SwingUtil;
import com.moral.util.UiTheme;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.SpinnerNumberModel;
import javax.swing.JFormattedTextField;
import javax.swing.text.NumberFormatter;
import javax.swing.text.DefaultFormatterFactory;

/**
 * 数据库连接配置卡片。
 */
public class ConnectionPanel extends JPanel {

    private final JComboBox<DbType> typeCombo = new JComboBox<>(DbType.values());
    private final JTextField hostField = new JTextField("127.0.0.1");
    private final JSpinner portField = new JSpinner(new SpinnerNumberModel(
            DbType.ORACLE.getDefaultPort(), 1, 65535, 1));
    private final JTextField databaseField = new JTextField();
    private final JTextField userField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JTextField schemaField = new JTextField("KBE");
    private final JButton testButton = new JButton("测试连接");
    private final JButton saveButton = new JButton("保存配置");
    private final JLabel statusLabel = new JLabel("未测试");
    private final JLabel databaseLabel = new JLabel("服务名/SID");

    /** 创建紧凑连接表单，相关字段同排显示。 */
    public ConnectionPanel() {
        setBorder(UiTheme.cardBorder("数据库连接"));
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
        // 端口号最大 5 位（65535），按字符数预留宽度并锁定最小宽度，避免窄窗口下被截
        JFormattedTextField portEditor = ((JSpinner.DefaultEditor) portField.getEditor()).getTextField();
        portEditor.setColumns(6);
        // 端口号是编号，不使用千分位分组显示。
        NumberFormatter formatter = new NumberFormatter(new java.text.DecimalFormat("0"));
        formatter.setAllowsInvalid(false);
        formatter.setCommitsOnValidEdit(true);
        formatter.setValueClass(Integer.class);
        formatter.setMinimum(1);
        formatter.setMaximum(65535);
        portEditor.setFormatterFactory(new DefaultFormatterFactory(formatter));
        int portHeight = portField.getPreferredSize().height;
        portField.setPreferredSize(new Dimension(96, portHeight));
        portField.setMinimumSize(new Dimension(96, portHeight));
        addRow(labelConstraints, fieldConstraints, row++, "主机",
                pairedFields(hostField, "端口", portField, 0));
        addRow(labelConstraints, fieldConstraints, row++, null, databaseLabel, databaseField);
        userField.setColumns(7);
        passwordField.setColumns(7);
        // 在密码框右侧显示眼睛按钮，点击切换明文和隐藏。
        passwordField.putClientProperty(FlatClientProperties.STYLE, "showRevealButton: true");
        configurePasswordIcons();
        addRow(labelConstraints, fieldConstraints, row++, "用户名",
                pairedFields(userField, "密码", passwordField, 1));
        addRow(labelConstraints, fieldConstraints, row++, "Schema", schemaField);

        // 测试连接是本卡片的主操作，强调显示
        UiTheme.primary(testButton);
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.add(testButton);
        buttonPanel.add(saveButton);
        JPanel actions = new JPanel(new BorderLayout(8, 0));
        actions.setOpaque(false);
        actions.add(buttonPanel, BorderLayout.WEST);
        statusLabel.setMinimumSize(new Dimension(0, statusLabel.getPreferredSize().height));
        statusLabel.setPreferredSize(new Dimension(100, statusLabel.getPreferredSize().height));
        UiTheme.muted(statusLabel);
        actions.add(statusLabel, BorderLayout.SOUTH);

        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.gridx = 0;
        buttonConstraints.gridy = row;
        buttonConstraints.gridwidth = 2;
        buttonConstraints.insets = new Insets(6, 8, 6, 8);
        buttonConstraints.fill = GridBagConstraints.HORIZONTAL;
        buttonConstraints.weightx = 1;
        add(actions, buttonConstraints);

        GridBagConstraints space = new GridBagConstraints();
        space.gridx = 0;
        space.gridy = row + 1;
        space.gridwidth = 2;
        space.weighty = 1;
        space.fill = GridBagConstraints.VERTICAL;
        JPanel filler = new JPanel();
        filler.setOpaque(false);
        add(filler, space);

        typeCombo.addActionListener(event -> onTypeChanged());
        testButton.addActionListener(event -> testConnection());
    }

    /** 图标和提示表示点击后的动作，沿用原有密码切换行为。 */
    private void configurePasswordIcons() {
        for (java.awt.Component component : passwordField.getComponents()) {
            if (component instanceof JToggleButton button
                    && "PasswordField.revealButton".equals(button.getName())) {
                button.setIcon(new PasswordVisibilityIcon(true));
                button.setSelectedIcon(new PasswordVisibilityIcon(false));
                updatePasswordAction(button);
                button.addItemListener(event -> updatePasswordAction(button));
            }
        }
    }

    /** 同步悬停提示和无障碍名称。 */
    private static void updatePasswordAction(JToggleButton button) {
        String action = button.isSelected() ? "隐藏密码" : "显示密码";
        button.setToolTipText(action);
        button.getAccessibleContext().setAccessibleName(action);
    }

    /** 将两个相关输入框放在一行，端口保持固定宽度。 */
    private JPanel pairedFields(java.awt.Component first, String label, java.awt.Component second, double secondWeight) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        panel.add(first, constraints);
        constraints.weightx = 0;
        constraints.insets = new Insets(0, 8, 0, 6);
        panel.add(new JLabel(label), constraints);
        constraints.weightx = secondWeight;
        constraints.insets = new Insets(0, 0, 0, 0);
        panel.add(second, constraints);
        return panel;
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
        String currentPort = String.valueOf(portField.getValue());
        if (isDefaultPort(currentPort)) {
            portField.setValue(type.getDefaultPort());
        }
        // MySQL 没有独立 schema 概念：库名即 schema，Schema 一栏不参与连接与建表
        boolean mysql = type == DbType.MYSQL;
        schemaField.setEnabled(!mysql);
        schemaField.setToolTipText(mysql
                ? "MySQL 的库就是 schema，无需填写；写入位置由「数据库名」决定"
                : null);
        if (mysql) {
            schemaField.setText("");
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
        config.setPort((Integer) portField.getValue());
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
        portField.setValue(Math.max(1, Math.min(65535, config.getPort())));
        databaseField.setText(config.getDatabase());
        userField.setText(config.getUser());
        passwordField.setText(config.getPassword());
        schemaField.setText(config.getSchema());
        databaseLabel.setText(config.getDbType().getDatabaseLabel());
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
        statusLabel.setForeground(ok ? UiTheme.STATUS_SUCCESS : UiTheme.STATUS_ERROR);
    }

    public JButton getSaveButton() {
        return saveButton;
    }
}
