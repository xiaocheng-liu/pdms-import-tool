package com.moral.ui;

import com.moral.model.CsvTable;
import com.moral.model.TableStatus;
import com.moral.util.FileSizeUtil;
import com.moral.util.UiTheme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

/**
 * 表清单：勾选、大小、状态、进度条、成功/失败行数、耗时。
 */
public class TableListPanel extends JPanel {

    private static final String[] COLUMNS = {"导入", "表名", "大小", "状态", "进度", "成功行", "失败行", "耗时", "说明"};

    private final List<CsvTable> tables = new ArrayList<>();
    private final CsvTableModel model = new CsvTableModel();
    private final JTable table = new JTable(model);
    private final JLabel summaryLabel = new JLabel("共 0 张表");

    /** 创建表清单，并为至少六行数据预留高度。 */
    public TableListPanel() {
        setBorder(UiTheme.cardBorder("表清单"));
        setLayout(new BorderLayout(4, 4));

        table.setRowHeight(28);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(3).setCellRenderer(new StatusRenderer());
        table.getColumnModel().getColumn(4).setCellRenderer(new ProgressRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(8).setPreferredWidth(260);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttonPanel.setOpaque(false);
        JButton selectAllButton = new JButton("全选");
        JButton selectNoneButton = new JButton("全不选");
        JButton invertButton = new JButton("反选");
        buttonPanel.add(selectAllButton);
        buttonPanel.add(selectNoneButton);
        buttonPanel.add(invertButton);

        selectAllButton.addActionListener(event -> setAllSelected(true));
        selectNoneButton.addActionListener(event -> setAllSelected(false));
        invertButton.addActionListener(event -> {
            for (CsvTable item : tables) {
                item.setSelected(!item.isSelected());
            }
            refresh();
            fireSelectionChanged();
        });

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setOpaque(false);
        bottomPanel.add(buttonPanel, BorderLayout.WEST);
        UiTheme.muted(summaryLabel);
        bottomPanel.add(summaryLabel, BorderLayout.EAST);

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
        int minimumHeight = table.getRowHeight() * 6 + table.getTableHeader().getPreferredSize().height
                + bottomPanel.getPreferredSize().height + getInsets().top + getInsets().bottom + 8;
        setMinimumSize(new Dimension(200, minimumHeight));
    }

    public void setTables(List<CsvTable> newTables) {
        tables.clear();
        tables.addAll(newTables);
        refresh();
    }

    public List<CsvTable> getTables() {
        return tables;
    }

    public List<CsvTable> getSelectedTables() {
        List<CsvTable> selected = new ArrayList<>();
        for (CsvTable item : tables) {
            if (item.isSelected()) {
                selected.add(item);
            }
        }
        return selected;
    }

    private void setAllSelected(boolean selected) {
        for (CsvTable item : tables) {
            item.setSelected(selected);
        }
        refresh();
        fireSelectionChanged();
    }

    /** 刷新表格内容（进度变化时调用） */
    public void refresh() {
        model.fireTableDataChanged();
        int selected = getSelectedTables().size();
        long bytes = 0;
        for (CsvTable item : tables) {
            if (item.isSelected()) {
                bytes += item.getSizeBytes();
            }
        }
        summaryLabel.setText("共 " + tables.size() + " 张表，已勾选 " + selected + " 张（" + FileSizeUtil.formatSize(bytes) + "）");
    }

    private void fireSelectionChanged() {
        // 由 MainFrame 监听刷新统计，此处仅触发表格重绘
        model.fireTableDataChanged();
    }

    /** 表格模型：直接读取 CsvTable 的实时进度 */
    private final class CsvTableModel extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return tables.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (columnIndex) {
                case 0:
                    return Boolean.class;
                case 4:
                    return Integer.class;
                case 5:
                case 6:
                    return Long.class;
                default:
                    return String.class;
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 0 && value instanceof Boolean) {
                tables.get(rowIndex).setSelected((Boolean) value);
                fireTableCellUpdated(rowIndex, columnIndex);
                refresh();
            }
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CsvTable item = tables.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return item.isSelected();
                case 1:
                    return item.getShardCount() > 1
                            ? item.getTableName() + "  [" + item.getShardCount() + " 分片]"
                            : item.getTableName();
                case 2:
                    return FileSizeUtil.formatSize(item.getSizeBytes());
                case 3:
                    return item.getProgress().getStatus().getLabel();
                case 4:
                    return item.getProgress().getPercent();
                case 5:
                    return item.getProgress().getSuccessRows();
                case 6:
                    return item.getProgress().getFailedRows();
                case 7:
                    return FileSizeUtil.formatDuration(item.getProgress().getElapsedMs());
                default:
                    return item.getProgress().getMessage();
            }
        }
    }

    /** 状态单元格：按状态着色的圆角标签 */
    private static final class StatusRenderer extends JLabel implements TableCellRenderer {

        private static final int ARC = 12;

        StatusRenderer() {
            setOpaque(false);
            setHorizontalAlignment(CENTER);
            setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                      boolean hasFocus, int row, int column) {
            TableStatus status = statusOf(value);
            setText(status.getLabel());
            setForeground(colorOf(status));
            return this;
        }

        /** 先画一个状态色淡底圆角块，再画文字，形成标签效果 */
        @Override
        protected void paintComponent(Graphics g) {
            Color color = getForeground();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 30));
                g2.fillRoundRect(0, 1, getWidth() - 1, getHeight() - 3, ARC, ARC);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** 进度条单元格 */
    private static final class ProgressRenderer extends JProgressBar implements TableCellRenderer {
        ProgressRenderer() {
            setStringPainted(true);
            setBorderPainted(false);
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                      boolean hasFocus, int row, int column) {
            int percent = value instanceof Integer ? (Integer) value : 0;
            setValue(percent);
            TableStatus status = statusOf(table.getValueAt(row, 3));
            // 数据已读完但仍在进行中，说明在等待数据库提交，给出明确提示
            if (status == TableStatus.RUNNING && percent >= 100) {
                setString("提交中...");
            } else {
                setString(percent + "%");
            }
            setForeground(colorOf(status));
            return this;
        }
    }

    /** 状态列的值是状态中文名，反查枚举；无法识别时按待导入处理 */
    private static TableStatus statusOf(Object value) {
        if (value instanceof TableStatus status) {
            return status;
        }
        String label = String.valueOf(value);
        for (TableStatus status : TableStatus.values()) {
            if (status.getLabel().equals(label)) {
                return status;
            }
        }
        return TableStatus.PENDING;
    }

    /** 状态与进度条共用的配色 */
    private static Color colorOf(TableStatus status) {
        switch (status) {
            case SUCCESS:
                return UiTheme.STATUS_SUCCESS;
            case FAILED:
                return UiTheme.STATUS_ERROR;
            case RUNNING:
                return UiTheme.STATUS_RUNNING;
            case CANCELLED:
                return UiTheme.STATUS_WARN;
            default:
                return UiTheme.STATUS_PENDING;
        }
    }
}
