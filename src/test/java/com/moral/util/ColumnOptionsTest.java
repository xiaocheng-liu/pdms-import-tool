package com.moral.util;

import com.moral.model.ConnectionConfig;
import com.moral.model.ImportOptions;
import com.moral.ui.OptionsPanel;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import java.util.Map;

import static org.junit.Assert.*;

/** 新增字段选项的旧配置兼容、持久化和界面禁用测试。 */
public class ColumnOptionsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** 组件测试不创建窗口，避免依赖桌面会话。 */
    @BeforeClass public static void headlessMode() { System.setProperty("java.awt.headless", "true"); }

    /** 旧配置不启用新增，新增与建表、扩长保持独立。 */
    @Test public void oldConfigurationDefaultsOff() {
        ImportOptions options = new ImportOptions();
        ConfigStore.applyTo(options, Map.of("opt.autoCreateTable", "true", "opt.syncColumnLengths", "true"));
        assertFalse(options.isAddMissingColumns());
        assertFalse(options.isSyncComments());
        ConfigStore.applyTo(options, Map.of("opt.addMissingColumns", "true"));
        assertTrue(options.isAddMissingColumns());
        assertFalse(options.isSyncColumnLengths());
    }

    /** 配置在临时用户目录往返，不读取或覆盖真实连接信息。 */
    @Test public void savesAndRestoresOption() {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", folder.getRoot().getAbsolutePath());
            ImportOptions options = new ImportOptions(); options.setAddMissingColumns(true); options.setSyncComments(true);
            ConfigStore.save(new ConnectionConfig(), options, "", false);
            ImportOptions loaded = new ImportOptions(); ConfigStore.applyTo(loaded, ConfigStore.load());
            assertTrue(loaded.isAddMissingColumns());
            assertTrue(loaded.isSyncComments());
            assertFalse(loaded.isSyncColumnLengths());
            assertFalse(loaded.isAutoCreateTable());
        } finally {
            System.setProperty("user.home", original);
        }
    }

    /** 在 Swing 事件线程检查选项回填、读取与准备期间禁用。 */
    @Test public void panelLoadsAndDisablesNewOption() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OptionsPanel panel = new OptionsPanel();
            ImportOptions saved = new ImportOptions(); saved.setAddMissingColumns(true); saved.setSyncComments(true);
            panel.loadFrom(saved);
            ImportOptions actual = new ImportOptions(); panel.applyTo(actual);
            assertTrue(actual.isAddMissingColumns());
            assertTrue(actual.isSyncComments());
            JCheckBox checkbox = null;
            for (var component : components(panel)) {
                if (component instanceof JCheckBox check && check.getText().equals("补充缺失字段")) checkbox = check;
            }
            assertNotNull(checkbox);
            panel.setEditable(false); assertFalse(checkbox.isEnabled());
            for (var component : components(panel)) {
                if (component instanceof JCheckBox check && check.getText().equals("同步表/字段注释")) assertFalse(check.isEnabled());
            }
            panel.setEditable(true); assertTrue(checkbox.isEnabled());
        });
    }
    /** 分组布局中递归查找控件，避免测试依赖面板层级。 */
    private static java.util.List<java.awt.Component> components(java.awt.Container parent) {
        java.util.List<java.awt.Component> result = new java.util.ArrayList<>();
        for (java.awt.Component child : parent.getComponents()) {
            result.add(child);
            if (child instanceof java.awt.Container container) result.addAll(components(container));
        }
        return result;
    }

}
