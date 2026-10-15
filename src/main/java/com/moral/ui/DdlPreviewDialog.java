package com.moral.ui;

import com.moral.util.UiTheme;
import com.moral.model.SchemaPlan;

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

    private final SchemaPlan plan;
    private String selectedTable;
    private final JTextArea details = new JTextArea(7, 60);
    private final JComboBox<String> tableCombo = new JComboBox<>();
    private final JTextArea ddlArea = new JTextArea();
    private boolean confirmed;

    public DdlPreviewDialog(Frame owner, Map<String, String> ddlMap) {
        this(owner, SchemaPlan.fromCreateSql(ddlMap));
    }

    /** 统一预览结构变更；建表可编辑，字段变更及检查结果只读。 */
    public DdlPreviewDialog(Frame owner, SchemaPlan plan) {
        super(owner, "结构变更预览", true);
        this.plan = plan;
        setLayout(new BorderLayout());

        List<String> names = new ArrayList<>(plan.tables().keySet());
        for (String name : names) {
            tableCombo.addItem(name);
        }

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        topPanel.setOpaque(false);
        topPanel.add(new JLabel("本次检查的表："));
        topPanel.add(tableCombo);
        JButton applyButton = new JButton("保存修改");
        topPanel.add(applyButton);

        ddlArea.setFont(UiTheme.monoFont());
        ddlArea.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        bottomPanel.setOpaque(false);
        JButton okButton = new JButton("确认执行并导入");
        JButton cancelButton = new JButton("取消");
        // 确认执行会真正建表并导入，作为主操作强调
        UiTheme.primary(okButton);
        bottomPanel.add(okButton);
        bottomPanel.add(cancelButton);

        add(topPanel, BorderLayout.NORTH);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(UiTheme.monoFont());
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(details), new JScrollPane(ddlArea));
        split.setResizeWeight(0.35);
        add(split, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        tableCombo.addActionListener(event -> { saveSelected(); showSelected(); });
        applyButton.addActionListener(event -> saveSelected());
        okButton.addActionListener(event -> {
            saveSelected();
            confirmed = true;
            dispose();
        });
        cancelButton.addActionListener(event -> dispose());

        showSelected();
        setSize(new Dimension(760, 520));
        setLocationRelativeTo(owner);
    }

    /** 切换表时保存建表编辑，字段变更文本始终不可写入计划。 */
    private void saveSelected() {
        if (selectedTable == null) return;
        SchemaPlan.Table table = plan.tables().get(selectedTable);
        if (table.createSql() != null && table.error().isEmpty()) table.setCreateSql(ddlArea.getText());
    }

    /** 显示单表状态、前后长度、单位及逐条 SQL。 */
    private void showSelected() {
        selectedTable = (String) tableCombo.getSelectedItem();
        if (selectedTable == null) return;
        SchemaPlan.Table table = plan.tables().get(selectedTable);
        StringBuilder summary = new StringBuilder("表：").append(selectedTable).append('\n');
        if (!table.error().isEmpty()) summary.append("检查失败，本表不清表、不导入：").append(table.error()).append('\n');
        else if (table.createSql() != null) summary.append("缺失表：建表 SQL 可编辑\n");
        else if (table.widenings().isEmpty() && table.additions().isEmpty() && table.comments().isEmpty()) summary.append("无可执行结构变更\n");
        for (SchemaPlan.Addition change : table.additions()) {
            summary.append(change.column()).append("：缺失 → ").append(change.declaration())
                    .append("，允许 NULL，不复制默认值与约束\n");
        }
        for (SchemaPlan.Widening change : table.widenings()) {
            summary.append(change.before().name()).append("：").append(change.before().display())
                    .append(" → ").append(change.length()).append(' ').append(change.before().unit()).append('\n');
        }
        for (SchemaPlan.Comment change : table.comments()) {
            summary.append(change.column().isEmpty() ? "表注释" : change.column() + " 注释")
                    .append("：").append(change.before()).append(" → ").append(change.after()).append('\n');
            if (table.createSql() != null && change.sql() != null) summary.append(change.sql()).append(";\n");
        }
        table.notes().forEach(note -> summary.append(note).append('\n'));
        details.setText(summary.toString());
        details.setCaretPosition(0);
        ddlArea.setEditable(table.createSql() != null && table.error().isEmpty());
        ddlArea.setText(table.createSql() != null ? table.createSql()
                : String.join(";\n\n", java.util.stream.Stream.of(table.additions().stream().map(SchemaPlan.Addition::sql),
                        table.widenings().stream().map(SchemaPlan.Widening::sql),
                        table.comments().stream().map(SchemaPlan.Comment::sql).filter(java.util.Objects::nonNull))
                        .flatMap(stream -> stream).toList()));
        ddlArea.setCaretPosition(0);
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public Map<String, String> getDdlMap() {
        Map<String, String> ddl = new LinkedHashMap<>();
        plan.tables().forEach((name, table) -> { if (table.createSql() != null) ddl.put(name, table.createSql()); });
        return ddl;
    }
}
