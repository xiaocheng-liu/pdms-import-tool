package com.moral.ui;

import com.moral.model.ImportOptions;
import com.moral.util.UiTheme;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JFormattedTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import javax.swing.text.NumberFormatter;
import javax.swing.text.DefaultFormatterFactory;

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
    private final JCheckBox autoCreateCheck = new JCheckBox("缺表时创建", true);
    private final JCheckBox syncCommentsCheck = new JCheckBox("同步表/字段注释", true);
    private final JCheckBox addColumnsCheck = new JCheckBox("补充缺失字段", true);
    private final JCheckBox syncLengthsCheck = new JCheckBox("字符字段扩长", true);
    private final JCheckBox emptyAsNullCheck = new JCheckBox("空字符串转 NULL");
    private final JCheckBox continueOnErrorCheck = new JCheckBox("失败后继续");
    private final JCheckBox sanitizeCrCheck = new JCheckBox("清洗裸回车");

    public OptionsPanel() {
        configureNumberFormat(threadsSpinner);
        configureNumberFormat(batchSpinner);
        configureNumberFormat(shardSpinner);
        configureNumberFormat(maxShardsSpinner);
        configureNumberFormat(writeTimeoutSpinner);
        setBorder(UiTheme.cardBorder("导入选项"));
        setLayout(new GridBagLayout());

        JPanel performance = new JPanel(new GridBagLayout());
        performance.setOpaque(false);
        addField(performance, 0, 0, "并发线程", threadsSpinner);
        addField(performance, 0, 1, "每批行数", batchSpinner);
        addField(performance, 0, 2, "分片阈值(MB)", shardSpinner);
        addField(performance, 1, 0, "单表分片上限", maxShardsSpinner);
        addField(performance, 1, 1, "写入超时(秒)", writeTimeoutSpinner);
        GridBagConstraints fast = new GridBagConstraints();
        fast.gridx = 4;
        fast.gridy = 1;
        fast.gridwidth = 2;
        fast.anchor = GridBagConstraints.WEST;
        fast.insets = new Insets(2, 10, 2, 0);
        performance.add(fastModeCheck, fast);
        addGroup("性能参数", performance, 0, false);

        JPanel data = new JPanel(new GridBagLayout());
        data.setOpaque(false);
        addField(data, 0, 0, "CSV 编码", encodingCombo);
        addField(data, 0, 1, "导入前清表", clearCombo);
        JPanel processing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        processing.setOpaque(false);
        processing.add(emptyAsNullCheck);
        processing.add(javax.swing.Box.createHorizontalStrut(20));
        processing.add(continueOnErrorCheck);
        processing.add(javax.swing.Box.createHorizontalStrut(20));
        processing.add(sanitizeCrCheck);
        GridBagConstraints checks = new GridBagConstraints();
        checks.gridx = 0;
        checks.gridy = 1;
        checks.gridwidth = 4;
        checks.weightx = 1;
        checks.fill = GridBagConstraints.HORIZONTAL;
        checks.insets = new Insets(2, 0, 2, 0);
        data.add(processing, checks);
        addGroup("数据处理", data, 1, false);

        JPanel structure = new JPanel(new GridLayout(1, 4, 8, 0));
        structure.setOpaque(false);
        structure.add(autoCreateCheck);
        structure.add(syncLengthsCheck);
        structure.add(addColumnsCheck);
        structure.add(syncCommentsCheck);
        addGroup("表结构", structure, 2, true);
        syncCommentsCheck.setToolTipText("同步 COMMENT ON TABLE/COLUMN 声明的表注释和 CSV 使用列注释，预览确认后执行；未声明的注释保持原样");
        addColumnsCheck.setToolTipText("只补充 CSV 使用且源脚本有定义的列，允许 NULL，不复制默认值及约束；预览确认后执行");
        syncLengthsCheck.setToolTipText("导入前按同目录 *_tables.sql 比较长度，仅扩长同类型字符字段，预览确认后执行");

        structure.setToolTipText("字段扩长、缺失字段和注释均按同目录 *_tables.sql 处理，执行前统一预览确认");
        emptyAsNullCheck.setToolTipText("将 CSV 中的空字符串按 NULL 写入目标数据库");
        continueOnErrorCheck.setToolTipText("数据写入失败时跳过失败行并继续，失败明细会记录到日志文件");
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

    /** 单行分组标题垂直居中，多行分组标题对齐首行。 */
    private void addGroup(String title, JPanel content, int row, boolean centerHeading) {
        JPanel group = new JPanel(new java.awt.BorderLayout(10, 0));
        group.setOpaque(false);
        group.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                row == 0 ? javax.swing.BorderFactory.createEmptyBorder()
                        : javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.BORDER),
                javax.swing.BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        JLabel heading = new JLabel(title);
        heading.setFont(UiTheme.boldFont());
        UiTheme.muted(heading);
        heading.setPreferredSize(new java.awt.Dimension(heading.getFontMetrics(heading.getFont()).stringWidth("性能参数"),
                heading.getPreferredSize().height));
        heading.setVerticalAlignment(centerHeading ? JLabel.CENTER : JLabel.TOP);
        if (!centerHeading) {
            heading.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 0, 0, 0));
        }
        group.add(heading, java.awt.BorderLayout.WEST);
        group.add(content, java.awt.BorderLayout.CENTER);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        add(group, constraints);
    }

    /** 参数成对排列，输入框保持紧凑宽度，多余空间留给列间距。 */
    private static void addField(JPanel panel, int row, int column, String title, javax.swing.JComponent input) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column * 2;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(2, column == 0 ? 0 : 10, 2, 6);
        panel.add(label(title), constraints);
        constraints.gridx++;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.NONE;
        constraints.insets = new Insets(2, 0, 2, 0);
        java.awt.Dimension size = input.getPreferredSize();
        int width = Math.max(88, size.width);
        input.setPreferredSize(new java.awt.Dimension(width, size.height));
        input.setMinimumSize(new java.awt.Dimension(width, size.height));
        panel.add(input, constraints);
    }

    /** 数字选项按编号显示，不使用千分位分组。 */
    private static void configureNumberFormat(JSpinner spinner) {
        JFormattedTextField editor = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
        NumberFormatter formatter = new NumberFormatter(new DecimalFormat("0"));
        formatter.setAllowsInvalid(false);
        formatter.setCommitsOnValidEdit(true);
        formatter.setValueClass(Integer.class);
        editor.setFormatterFactory(new DefaultFormatterFactory(formatter));
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
        options.setSyncColumnLengths(syncLengthsCheck.isSelected());
        options.setAddMissingColumns(addColumnsCheck.isSelected());
        options.setSyncComments(syncCommentsCheck.isSelected());
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
        syncLengthsCheck.setSelected(options.isSyncColumnLengths());
        addColumnsCheck.setSelected(options.isAddMissingColumns());
        syncCommentsCheck.setSelected(options.isSyncComments());
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
        syncLengthsCheck.setEnabled(editable);
        addColumnsCheck.setEnabled(editable);
        syncCommentsCheck.setEnabled(editable);
        emptyAsNullCheck.setEnabled(editable);
        continueOnErrorCheck.setEnabled(editable);
        sanitizeCrCheck.setEnabled(editable);
    }
}
