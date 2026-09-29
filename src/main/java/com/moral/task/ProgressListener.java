package com.moral.task;

import com.moral.model.CsvTable;
import com.moral.model.ImportResult;

/**
 * 导入引擎向界面回调的事件。
 */
public interface ProgressListener {

    /** 日志输出 */
    void onLog(String message);

    /** 定时刷新（默认 500ms 一次），界面直接读取 TableProgress 渲染 */
    void onTick();

    /** 单表完成 */
    void onTableFinished(CsvTable table);

    /** 全部完成 */
    void onAllFinished(ImportResult result);
}
