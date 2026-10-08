package com.moral.ui;

import com.moral.model.ImportOptions;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 导入选项卡片：并发、批量、分片、编码、清表、极速模式与开关组。
 */
public class OptionsPanel extends JPanel {

    private final JSpinner threadsSpinner = new JSpinner(new SpinnerNumberModel(6, 1, 32, 1));
    private final JSpinner batchSpinner = new JSpinner(new SpinnerNumberModel(5000, 1, 50000, 1000));
    private final JSpinner shardSpinner = new JSpinner(new SpinnerNumberModel(100, 1, 4096, 50));
    private final JSpinner maxShardsSpinner = new JSpinner(new SpinnerNumberModel(8, 1, 64, 1));
    private final JSpinner writeTimeoutSpinner = new JSpinner(new SpinnerNumberModel(120, 0, 3600, 30));
    private final JComboBox<String> encodingCombo = new JComboBox<>(new String[]{"UTF-8", "GBK"});
    private final JComboBox<ImportOptions.ClearMode> clearCombo =
            new JComboBox<>(ImportOptions.ClearMode.values());
    private final JCheckBox fastModeCheck = new JCheckBox("极速模式");
    private final JCheckBox autoCreateCheck = new JCheckBox("表不存在时自动建表");
    private final JCheckBox emptyAsNullCheck = new JCheckBox("空字符串转 NULL");
    private final JCheckBox continueOnErrorCheck = new JCheckBox("失败跳过继续");
    private final JCheckBox sanitizeCrCheck = new JCheckBox("清洗未加引号的回车符");

    public OptionsPanel() {
        setBorder(BorderFactory.createTitledBorder("导入选项"));
        setLayout(new GridBagLayout());

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(2, 6, 2, 12);

        // 第一行：并发、批量、分片阈值
        constraints.gridy = 0;
        constraints.gridx = 0;
        add(label("并发线程数"), constraints);
        constraints.gridx = 1;
        add(threadsSpinner, constraints);

        constraints.gridx = 2;
        add(label("批量提交行数"), constraints);
        constraints.gridx = 3;
        add(batchSpinner, constraints);

        constraints.gridx = 4;
        add(label("大表分片阈值(MB)"), constraints);
        constraints.gridx = 5;
        add(shardSpinner, constraints);

        // 第二行：单表分片上限、编码、清表方式
        constraints.gridy = 1;
        constraints.gridx = 0;
        add(label("单表最大分片数"), constraints);
        constraints.gridx = 1;
        add(maxShardsSpinner, constraints);

        constraints.gridx = 2;
        add(label("CSV 编码"), constraints);
        constraints.gridx = 3;
        add(encodingCombo, constraints);

        constraints.gridx = 4;
        add(label("导入前清表"), constraints);
        constraints.gridx = 5;
        add(clearCombo, constraints);

        // 第三行：写入超时（0 表示关闭自动中断）
        constraints.gridy = 2;
        constraints.gridx = 0;
        add(label("写入超时(秒)"), constraints);
        constraints.gridx = 1;
        add(writeTimeoutSpinner, constraints);

        // 第四行：开关组单独一行，避免被压缩或裁切
        JPanel checkPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        checkPanel.add(fastModeCheck);
        checkPanel.add(autoCreateCheck);
        checkPanel.add(emptyAsNullCheck);
        checkPanel.add(continueOnErrorCheck);
        checkPanel.add(sanitizeCrCheck);
        constraints.gridy = 3;
        constraints.gridx = 0;
        constraints.gridwidth = 6;
        add(checkPanel, constraints);

        emptyAsNullCheck.setSelected(true);
        continueOnErrorCheck.setSelected(true);
        threadsSpinner.setToolTipText("并发线程数同时决定最大数据库连接数");
        batchSpinner.setToolTipText("每多少行提交一次，调大可提升吞吐（建议 2000~10000）");
        shardSpinner.setToolTipText("超过该体积的 CSV 将拆分为多个分片并行导入");
        maxShardsSpinner.setToolTipText("单张表最多拆成几个分片，实际取 min(并发线程数, 该值)");
        writeTimeoutSpinner.setToolTipText("<html>单个批次执行/提交超过该秒数仍无进展时，自动取消当前批次<br>"
                + "极速模式下会自动降级为标准写入；标准模式下该表标记失败<br>"
                + "填 0 表示关闭自动中断（仍保留连接层网络超时兜底）</html>");
        fastModeCheck.setToolTipText("<html>极速模式：PostgreSQL / 人大金仓走 COPY 批量加载，MySQL 走 LOAD DATA LOCAL INFILE，Oracle/达梦走 APPEND 直接路径插入（单分片）<br>"
                + "失败会自动降级为标准 INSERT，数据不会丢；降级时失败行定位能力略有下降</html>");
        autoCreateCheck.setToolTipText("表在目标库中不存在时，按 CSV 采样推断类型生成建表语句（执行前可预览编辑）");
        sanitizeCrCheck.setToolTipText("<html>CSV 中存在未加引号的回车符时，PostgreSQL COPY 会报"
                + "「unquoted carriage return found in data」并降级为标准写入，标准模式也会把该行拆成两行<br>"
                + "勾选后：引号外的裸回车符会被替换为空格，行结构保持不变，可继续走极速模式<br>"
                + "注意：这会改动原始字段内容，日志会提示替换数量</html>");
    }

    /** 标签禁止被压缩，避免窄窗口下显示成省略号 */
    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setMinimumSize(label.getPreferredSize());
        return label;
    }

    /** 把界面选项写入目标对象 */
    public void applyTo(ImportOptions options) {
        options.setThreads((Integer) threadsSpinner.getValue());
        options.setBatchSize((Integer) batchSpinner.getValue());
        options.setShardThresholdMb((Integer) shardSpinner.getValue());
        options.setMaxShardsPerTable((Integer) maxShardsSpinner.getValue());
        options.setWriteTimeoutSeconds((Integer) writeTimeoutSpinner.getValue());
        options.setEncoding("GBK".equals(encodingCombo.getSelectedItem()) ? Charset.forName("GBK") : StandardCharsets.UTF_8);
        options.setClearMode((ImportOptions.ClearMode) clearCombo.getSelectedItem());
        options.setFastMode(fastModeCheck.isSelected());
        options.setAutoCreateTable(autoCreateCheck.isSelected());
        options.setEmptyAsNull(emptyAsNullCheck.isSelected());
        options.setContinueOnError(continueOnErrorCheck.isSelected());
        options.setSanitizeCarriageReturn(sanitizeCrCheck.isSelected());
    }

    /** 用配置对象回填界面 */
    public void loadFrom(ImportOptions options) {
        threadsSpinner.setValue(options.getThreads());
        batchSpinner.setValue(options.getBatchSize());
        shardSpinner.setValue(options.getShardThresholdMb());
        maxShardsSpinner.setValue(options.getMaxShardsPerTable());
        writeTimeoutSpinner.setValue(options.getWriteTimeoutSeconds());
        encodingCombo.setSelectedItem("GBK".equalsIgnoreCase(options.getEncoding().name()) ? "GBK" : "UTF-8");
        clearCombo.setSelectedItem(options.getClearMode());
        fastModeCheck.setSelected(options.isFastMode());
        autoCreateCheck.setSelected(options.isAutoCreateTable());
        emptyAsNullCheck.setSelected(options.isEmptyAsNull());
        continueOnErrorCheck.setSelected(options.isContinueOnError());
        sanitizeCrCheck.setSelected(options.isSanitizeCarriageReturn());
    }

    /** 导入进行中禁用所有选项，避免中途改参数 */
    public void setEditable(boolean editable) {
        threadsSpinner.setEnabled(editable);
        batchSpinner.setEnabled(editable);
        shardSpinner.setEnabled(editable);
        maxShardsSpinner.setEnabled(editable);
        writeTimeoutSpinner.setEnabled(editable);
        encodingCombo.setEnabled(editable);
        clearCombo.setEnabled(editable);
        fastModeCheck.setEnabled(editable);
        autoCreateCheck.setEnabled(editable);
        emptyAsNullCheck.setEnabled(editable);
        continueOnErrorCheck.setEnabled(editable);
        sanitizeCrCheck.setEnabled(editable);
    }
}
