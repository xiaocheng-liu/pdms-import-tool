package com.moral.util;

import com.formdev.flatlaf.icons.FlatAbstractIcon;

import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/** 密码可见性图标：明文显示睁眼，隐藏显示带斜线的眼睛。 */
public final class PasswordVisibilityIcon extends FlatAbstractIcon {

    private final boolean visible;

    /** 创建对应密码可见状态的图标，缩放由 FlatLaf 处理。 */
    public PasswordVisibilityIcon(boolean visible) {
        super(16, 16, UIManager.getColor("PasswordField.revealIconColor"));
        this.visible = visible;
    }

    /** 绘制眼睛轮廓，并在隐藏状态叠加斜线。 */
    @Override
    protected void paintIcon(Component component, Graphics2D graphics) {
        graphics.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D eye = new Path2D.Float();
        eye.moveTo(1.5, 8);
        eye.curveTo(5, 2.5, 11, 2.5, 14.5, 8);
        eye.curveTo(11, 13.5, 5, 13.5, 1.5, 8);
        eye.closePath();
        graphics.draw(eye);
        graphics.draw(new Ellipse2D.Float(5.75f, 5.75f, 4.5f, 4.5f));
        if (!visible) {
            graphics.draw(new Line2D.Float(2, 2, 14, 14));
        }
    }
}
