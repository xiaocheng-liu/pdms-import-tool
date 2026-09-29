package com.moral.ui;

import com.moral.model.ConnectionConfig;
import com.moral.model.CsvTable;
import com.moral.model.ImportOptions;
import com.moral.model.ImportResult;
import com.moral.model.TableProgress;
import com.moral.model.TableStatus;
import com.moral.task.FailureRecorder;
import com.moral.task.ImportEngine;
import com.moral.task.ProgressListener;
import com.moral.util.ConfigStore;
import com.moral.util.FileSizeUtil;
import com.moral.util.SwingUtil;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主窗口：连接配置 → 文件夹扫描 → 表清单 → 导入选项 → 并发导入与日志。
 */
public class MainFrame extends JFrame {

    private final ConnectionPanel connectionPanel = new ConnectionPanel();
    private final FolderPanel folderPanel = new FolderPanel();
    private final OptionsPanel optionsPanel = new OptionsPanel();
    private final TableListPanel tableListPanel = new TableListPanel();
    private final LogPanel logPanel = new LogPanel();
    private final ImportOptions options = new ImportOptions();
    private final ImportEngine engine = new ImportEngine();

    private final JButton startButton = new JButton("开始导入");
    private final JButton stopButton = new JButton("停止");
    private final JButton exportButton = new JButton("导出失败明细");
    private final JProgressBar totalProgress = new JProgressBar(0, 100);
    private final JLabel totalLabel = new JLabel("就绪");

    private SwingWorker<Void, Void> worker;
    private ImportResult lastResult;
    private JSplitPane splitPane;
    private JSplitPane mainSplitPane;
    private JPanel configPanel;
    private long importStartedAt;
    /** 剩余时间估算用的近期速率（字节/毫秒），<=0 表示暂时无法估算 */
    private long lastProgressAt;
    private long lastProgressBytes;
    private double smoothRateBytesPerMs = -1;
    private boolean running;

    public MainFrame() {
        super("PDMS 数据导入工具");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        buildUi();
        bindEvents();
        loadConfig();
        setSize(1280, 900);
        setMinimumSize(new Dimension(1150, 700));
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 10, 10));
        setContentPane(root);

        root.add(buildHeader(), BorderLayout.NORTH);

        configPanel = buildConfigPanel();
        // 配置区放入滚动容器：窗口再小也不会把卡片压扁（内容超长时出现滚动条）
        JScrollPane configScroll = new JScrollPane(configPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        configScroll.setBorder(null);
        configScroll.setOpaque(false);
        configScroll.getViewport().setOpaque(false);
        configScroll.getVerticalScrollBar().setUnitIncrement(16);

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, configScroll, tableListPanel);
        splitPane.setResizeWeight(0);
        splitPane.setContinuousLayout(true);
        splitPane.setDividerLocation(configPanel.getPreferredSize().height + 12);
        splitPane.setMinimumSize(new Dimension(200, 140));

        // 日志区同样放进分割条，可上下拖动调整高度
        logPanel.setPreferredSize(new Dimension(1000, 180));
        logPanel.setMinimumSize(new Dimension(200, 100));
        mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, splitPane, logPanel);
        mainSplitPane.setResizeWeight(0.78);
        mainSplitPane.setDividerLocation(0.78);
        mainSplitPane.setContinuousLayout(true);

        root.add(mainSplitPane, BorderLayout.CENTER);
        root.add(buildActionBar(), BorderLayout.SOUTH);

        // 窗口完成布局后按实际内容高度再校正一次分割条，保证"数据源文件夹"完整可见
        SwingUtilities.invokeLater(() -> {
            int height = configPanel.getPreferredSize().height + 12;
            splitPane.setDividerLocation(height);
            if (mainSplitPane.getHeight() > 0) {
                mainSplitPane.setDividerLocation(Math.max(240, mainSplitPane.getHeight() - 200));
            }
        });
    }

    /**
     * 配置区采用左右两栏布局：左侧数据库连接，右侧数据源文件夹 + 导入选项。
     * 相比纵向堆叠可显著降低整体高度，避免小窗口下卡片被压扁。
     */
    private JPanel buildConfigPanel() {
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
        folderPanel.setAlignmentX(LEFT_ALIGNMENT);
        optionsPanel.setAlignmentX(LEFT_ALIGNMENT);
        lockHeight(folderPanel);
        lockHeight(optionsPanel);
        rightPanel.add(folderPanel);
        rightPanel.add(javax.swing.Box.createVerticalStrut(4));
        rightPanel.add(optionsPanel);

        Dimension connectionPreferred = connectionPanel.getPreferredSize();
        connectionPanel.setPreferredSize(new Dimension(Math.max(400, connectionPreferred.width),
                connectionPreferred.height));

        // 使用支持"宽度跟随视口"的面板，避免宽度溢出导致右侧按钮被裁掉
        JPanel panel = new ScrollablePanel(new BorderLayout(10, 0));
        panel.add(connectionPanel, BorderLayout.WEST);
        panel.add(rightPanel, BorderLayout.CENTER);
        return panel;
    }

    /** 宽度跟随视口、高度按内容的面板，放入 JScrollPane 后不会横向溢出 */
    private static final class ScrollablePanel extends JPanel implements javax.swing.Scrollable {

        ScrollablePanel(java.awt.LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 80;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** 固定组件高度为其首选高度，防止 BoxLayout 在空间不足时把卡片压扁 */
    private static void lockHeight(javax.swing.JComponent component) {
        Dimension preferred = component.getPreferredSize();
        component.setMinimumSize(new Dimension(0, preferred.height));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 4));
        JLabel title = new JLabel("PDMS 数据导入工具");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        JLabel subtitle = new JLabel("Oracle / PostgreSQL / 达梦 · CSV 文件夹批量并发导入");
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);
        textPanel.add(title);
        textPanel.add(subtitle);

        header.add(textPanel, BorderLayout.WEST);
        return header;
    }

    /** 底部操作栏：开始/停止/导出 + 总体进度（固定高度，不随分割条变化） */
    private JPanel buildActionBar() {
        JPanel actionBar = new JPanel(new BorderLayout(10, 0));
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionPanel.add(startButton);
        actionPanel.add(stopButton);
        actionPanel.add(exportButton);

        JPanel progressPanel = new JPanel(new BorderLayout(10, 0));
        totalProgress.setStringPainted(true);
        progressPanel.add(totalProgress, BorderLayout.CENTER);
        progressPanel.add(totalLabel, BorderLayout.EAST);

        actionBar.add(actionPanel, BorderLayout.WEST);
        actionBar.add(progressPanel, BorderLayout.CENTER);

        stopButton.setEnabled(false);
        exportButton.setEnabled(false);
        return actionBar;
    }

    private void bindEvents() {
        folderPanel.getScanButton().addActionListener(event -> scanFolder());
        connectionPanel.getSaveButton().addActionListener(event -> {
            saveConfig();
            logPanel.append("配置已保存到 " + ConfigStore.configFile().getAbsolutePath(), LogPanel.Level.SUCCESS);
        });
        startButton.addActionListener(event -> startImport());
        stopButton.addActionListener(event -> stopImport());
        exportButton.addActionListener(event -> exportFailures());
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                if (running && !SwingUtil.confirm(MainFrame.this, "导入正在进行，确认退出吗？")) {
                    return;
                }
                saveConfig();
                dispose();
                System.exit(0);
            }
        });
    }

    private void loadConfig() {
        Map<String, String> data = ConfigStore.load();
        if (data.isEmpty()) {
            return;
        }
        ConnectionConfig config = new ConnectionConfig();
        ConfigStore.applyTo(config, data);
        connectionPanel.applyConfig(config);
        ConfigStore.applyTo(options, data);
        optionsPanel.loadFrom(options);
        String lastDir = ConfigStore.lastDir(data);
        if (!lastDir.isEmpty()) {
            folderPanel.setDirectory(lastDir);
        }
        logPanel.append("已加载上次配置", LogPanel.Level.INFO);
    }

    private void saveConfig() {
        optionsPanel.applyTo(options);
        ConfigStore.save(connectionPanel.buildConfig(), options, folderPanel.getDirectory(), true);
    }

    private void scanFolder() {
        String directory = folderPanel.getDirectory();
        File folder = new File(directory);
        if (!folder.isDirectory()) {
            SwingUtil.showError(this, "文件夹不存在：" + directory);
            return;
        }
        File[] files = folder.listFiles(file -> file.isFile() && file.getName().toLowerCase().endsWith(".csv"));
        if (files == null || files.length == 0) {
            SwingUtil.showError(this, "该文件夹下没有找到 .csv 文件");
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::length).reversed());
        List<CsvTable> tables = new ArrayList<>(files.length);
        long total = 0;
        for (File file : files) {
            tables.add(new CsvTable(file));
            total += file.length();
        }
        tableListPanel.setTables(tables);
        folderPanel.setStats(tables.size(), total, tables.size());
        logPanel.append("扫描到 " + tables.size() + " 个 CSV，合计 " + FileSizeUtil.formatSize(total), LogPanel.Level.SUCCESS);
    }

    private void startImport() {
        ConnectionConfig config = connectionPanel.buildConfig();
        String error = config.validate();
        if (error != null) {
            SwingUtil.showError(this, error);
            return;
        }
        optionsPanel.applyTo(options);
        List<CsvTable> selected = tableListPanel.getSelectedTables();
        if (selected.isEmpty()) {
            SwingUtil.showError(this, "请至少勾选一张要导入的表");
            return;
        }
        if (options.getClearMode() != ImportOptions.ClearMode.NONE
                && !SwingUtil.confirm(this, "确认在导入前清空这 " + selected.size() + " 张表吗？该操作不可恢复！")) {
            return;
        }
        saveConfig();
        prepareAndRun(config, selected);
    }

    /** 自动建表时先生成 DDL 供预览确认，再执行导入 */
    private void prepareAndRun(ConnectionConfig config, List<CsvTable> selected) {
        startButton.setEnabled(false);
        if (!options.isAutoCreateTable()) {
            runImport(config, selected, new LinkedHashMap<String, String>());
            return;
        }
        logPanel.append("正在检查目标库中缺失的表...", LogPanel.Level.INFO);
        new SwingWorker<Map<String, String>, Void>() {
            @Override
            protected Map<String, String> doInBackground() throws Exception {
                return engine.generateMissingDdl(selected, config, options);
            }

            @Override
            protected void done() {
                Map<String, String> ddlMap;
                try {
                    ddlMap = get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    logPanel.append("检查表结构失败：" + cause.getMessage(), LogPanel.Level.ERROR);
                    SwingUtil.showError(MainFrame.this, "检查表结构失败：" + cause.getMessage());
                    startButton.setEnabled(true);
                    return;
                }
                if (ddlMap.isEmpty()) {
                    logPanel.append("目标库表结构完整，无需建表", LogPanel.Level.SUCCESS);
                    runImport(config, selected, ddlMap);
                    return;
                }
                logPanel.append("发现 " + ddlMap.size() + " 张表在目标库中不存在，已生成建表语句", LogPanel.Level.WARN);
                DdlPreviewDialog dialog = new DdlPreviewDialog(MainFrame.this, ddlMap);
                dialog.setVisible(true);
                if (!dialog.isConfirmed()) {
                    logPanel.append("已取消导入", LogPanel.Level.WARN);
                    startButton.setEnabled(true);
                    return;
                }
                runImport(config, selected, dialog.getDdlMap());
            }
        }.execute();
    }

    private void runImport(ConnectionConfig config, List<CsvTable> selected, Map<String, String> ddlMap) {
        running = true;
        importStartedAt = System.currentTimeMillis();
        lastProgressAt = 0;
        lastProgressBytes = 0;
        smoothRateBytesPerMs = -1;
        startButton.setText("导入中...");
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        exportButton.setEnabled(false);
        optionsPanel.setEditable(false);
        for (CsvTable table : selected) {
            table.resetProgress();
        }
        tableListPanel.refresh();
        logPanel.append("开始并发导入，线程数 " + options.getThreads()
                + "，批量 " + options.getBatchSize() + " 行/批", LogPanel.Level.INFO);

        worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    lastResult = engine.start(selected, config, options, ddlMap, listener);
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    logPanel.append("导入失败：" + cause.getMessage(), LogPanel.Level.ERROR);
                    SwingUtil.showError(MainFrame.this, "导入失败：" + cause.getMessage());
                }
                return null;
            }

            @Override
            protected void done() {
                finishImport();
            }
        };
        worker.execute();
    }

    private void stopImport() {
        if (!running) {
            return;
        }
        engine.cancel();
        stopButton.setEnabled(false);
        logPanel.append("正在停止，已提交的数据不会回滚...", LogPanel.Level.WARN);
    }

    private void finishImport() {
        running = false;
        startButton.setText("开始导入");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        optionsPanel.setEditable(true);
        updateTotalProgress();
        if (lastResult == null) {
            return;
        }
        exportButton.setEnabled(true);
        String summary = "导入完成：共 " + lastResult.getTotalTables() + " 张表，成功 "
                + lastResult.getSuccessTables() + " 张，失败 " + lastResult.getFailedTables() + " 张；成功 "
                + FileSizeUtil.formatNumber(lastResult.getSuccessRows()) + " 行，失败 "
                + FileSizeUtil.formatNumber(lastResult.getFailedRows()) + " 行；耗时 "
                + FileSizeUtil.formatDuration(lastResult.getElapsedMs());
        logPanel.append(summary, lastResult.getFailedTables() == 0 ? LogPanel.Level.SUCCESS : LogPanel.Level.WARN);

        // 失败行明细：实时落盘文件 + 导出入口提示
        if (lastResult.getFailedRows() > 0 || !lastResult.getFailures().isEmpty()) {
            String failureLog = lastResult.getFailureLogFile();
            String hint = "失败行明细共 " + lastResult.getFailures().size() + " 条";
            if (failureLog != null) {
                hint += "，已实时写入：" + failureLog;
            }
            hint += "；可点「导出失败明细」另存为制表符表格文件";
            logPanel.append(hint, LogPanel.Level.WARN);
            summary += "\n\n" + hint;
        }
        SwingUtil.showInfo(this, summary);
    }

    private void exportFailures() {
        if (lastResult == null || lastResult.getFailures().isEmpty()) {
            SwingUtil.showInfo(this, "没有失败明细需要导出");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        String name = "导入失败明细_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".txt";
        chooser.setSelectedFile(new File(name));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        try {
            int count = FailureRecorder.export(lastResult.getFailures(), target);
            logPanel.append("已导出 " + count + " 条失败明细：" + target.getAbsolutePath(), LogPanel.Level.SUCCESS);
            SwingUtil.showInfo(this, "已导出 " + count + " 条失败明细");
        } catch (Exception e) {
            SwingUtil.showError(this, "导出失败：" + e.getMessage());
        }
    }

    /** 按日志内容判断颜色级别：失败红、警告黄、其余默认色 */
    private static LogPanel.Level messagingLevel(String message) {
        if (message == null) {
            return LogPanel.Level.INFO;
        }
        if (message.startsWith("[失败")) {
            return LogPanel.Level.ERROR;
        }
        if (message.startsWith("[警告") || message.startsWith("失败行")) {
            return LogPanel.Level.WARN;
        }
        return LogPanel.Level.INFO;
    }

    /** 引擎进度回调：日志与界面刷新 */
    private final ProgressListener listener = new ProgressListener() {
        @Override
        public void onLog(String message) {
            LogPanel.Level level = messagingLevel(message);
            logPanel.append(message, level);
        }

        @Override
        public void onTick() {
            SwingUtil.invoke(() -> {
                tableListPanel.refresh();
                updateTotalProgress();
            });
        }

        @Override
        public void onTableFinished(CsvTable table) {
            TableProgress progress = table.getProgress();
            if (progress.getStatus() == TableStatus.FAILED) {
                logPanel.append("[失败] " + table.getTableName() + "：" + progress.getMessage(), LogPanel.Level.ERROR);
            } else {
                logPanel.append("[完成] " + table.getTableName() + "：" + progress.getSuccessRows() + " 行", LogPanel.Level.SUCCESS);
            }
        }

        @Override
        public void onAllFinished(ImportResult result) {
            tableListPanel.refresh();
        }
    };

    /**
     * 更新总体进度：百分比保留两位小数；剩余时间按"近期速率"推算，
     * 数据停止流动时显示等待提示，避免样本不足时出现"剩余 89 小时"这类误导数字。
     */
    private void updateTotalProgress() {
        long total = 0;
        long done = 0;
        long rowsWritten = 0;
        long rowsFailed = 0;
        int finished = 0;
        int totalTables = 0;
        for (CsvTable table : tableListPanel.getTables()) {
            if (!table.isSelected()) {
                continue;
            }
            totalTables++;
            TableProgress progress = table.getProgress();
            total += progress.getBytesTotal();
            done += progress.getBytesDone();
            rowsWritten += progress.getSuccessRows();
            rowsFailed += progress.getFailedRows();
            if (progress.getStatus() != TableStatus.PENDING && progress.getStatus() != TableStatus.RUNNING) {
                finished++;
            }
        }

        long now = System.currentTimeMillis();
        if (lastProgressAt > 0 && now > lastProgressAt) {
            double instant = (done - lastProgressBytes) / (double) (now - lastProgressAt);
            smoothRateBytesPerMs = smoothRateBytesPerMs <= 0
                    ? instant
                    : smoothRateBytesPerMs * 0.7 + instant * 0.3;
        }
        lastProgressAt = now;
        lastProgressBytes = done;

        String percentText = total <= 0 ? "0.00%" : String.format("%.2f%%", done * 100.0 / total);
        totalProgress.setValue(total <= 0 ? 0 : (int) (done * 100L / total));
        totalProgress.setString(percentText);

        String etaText;
        if (total > 0 && done >= total) {
            etaText = "等待收尾提交";
        } else if (!running) {
            etaText = "—";
        } else if (smoothRateBytesPerMs <= 0.0005) {
            etaText = "数据暂无推进，等待数据库写入响应（详见日志批次耗时）";
        } else {
            long remainMs = (long) ((total - done) / smoothRateBytesPerMs);
            etaText = "预计剩余 " + FileSizeUtil.formatDuration(remainMs);
        }

        totalLabel.setText("已完成 " + finished + "/" + totalTables + " 张表　已读 "
                + FileSizeUtil.formatSize(done) + " / " + FileSizeUtil.formatSize(total)
                + "　写入 " + FileSizeUtil.formatNumber(rowsWritten) + " 行"
                + (rowsFailed > 0 ? "（失败 " + FileSizeUtil.formatNumber(rowsFailed) + "）" : "")
                + "　" + etaText);
    }
}
