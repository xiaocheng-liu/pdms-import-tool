package com.moral.util;

import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.Taskbar;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 应用图标加载：窗口标题栏、任务栏/Dock、应用打包统一使用同一套图标。
 */
public final class AppIcons {

    private static final List<String> ICON_PATHS = Arrays.asList(
            "icons/pdms-16.png",
            "icons/pdms-32.png",
            "icons/pdms-48.png",
            "icons/pdms-64.png",
            "icons/pdms-128.png",
            "icons/pdms-256.png",
            "icons/pdms-512.png",
            "icons/pdms-1024.png"
    );

    private static List<Image> icons;

    private AppIcons() {
    }

    /** 加载所有尺寸图标；缺失任意一张都不影响，其余仍可用 */
    public static synchronized List<Image> loadIcons() {
        if (icons != null) {
            return icons;
        }
        List<Image> list = new ArrayList<>(ICON_PATHS.size());
        for (String path : ICON_PATHS) {
            URL url = AppIcons.class.getClassLoader().getResource(path);
            if (url != null) {
                try {
                    list.add(new ImageIcon(url).getImage());
                } catch (Exception ignore) {
                    // 单张图标加载失败不影响整体
                }
            }
        }
        icons = Collections.unmodifiableList(list);
        return icons;
    }

    /**
     * 应用图标到窗口。
     * <ul>
     *   <li>Windows / Linux：窗口标题栏和任务栏；</li>
     *   <li>macOS：窗口标题栏；Dock 图标建议通过打包 .app 的 ICNS 设置。</li>
     * </ul>
     */
    public static void applyTo(java.awt.Window window) {
        List<Image> images = loadIcons();
        if (!images.isEmpty()) {
            window.setIconImages(images);
        }
    }

    /** 尝试设置系统任务栏/Dock 图标（Java 9+ 在 macOS/Windows 上受支持）。 */
    public static void applyToTaskbar() {
        List<Image> images = loadIcons();
        if (images.isEmpty()) {
            return;
        }
        try {
            if (Taskbar.isTaskbarSupported()
                    && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                // 取 256 或最大可用尺寸给 Dock/任务栏
                Image image = images.stream()
                        .filter(img -> img.getWidth(null) >= 128)
                        .findFirst()
                        .orElse(images.get(images.size() - 1));
                Taskbar.getTaskbar().setIconImage(image);
            }
        } catch (Exception ignore) {
            // 非 GUI 环境或不支持时不影响启动
        }
    }
}
