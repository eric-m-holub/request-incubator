package com.ericholub.requestincubator;

import javax.swing.*;
import java.awt.*;


/**
 * Text field that paints a gray hint while it is empty.
 * The hint follows the field's horizontal alignment (so it is centered when the text is).
 */
public class HintTextField extends JTextField {

    private final String hint;

    HintTextField(String text, String hint) {
        super(text);
        this.hint = hint;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (!getText().isEmpty() || hint == null || hint.isEmpty()) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();

        try {
            g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );

            Color hintColor = UIManager.getColor("TextField.inactiveForeground");
            g2.setColor(hintColor != null ? hintColor : Color.GRAY);
            g2.setFont(getFont());

            FontMetrics fm = g2.getFontMetrics();
            Insets in = getInsets();

            int available = getWidth() - in.left - in.right;
            int textWidth = fm.stringWidth(hint);

            int x;
            switch (getHorizontalAlignment()) {
                case JTextField.CENTER:
                    x = in.left + (available - textWidth) / 2;
                    break;
                case JTextField.RIGHT:
                case JTextField.TRAILING:
                    x = in.left + available - textWidth;
                    break;
                default:
                    x = in.left;
            }

            int contentHeight = getHeight() - in.top - in.bottom;
            int y = in.top + (contentHeight - fm.getHeight()) / 2 + fm.getAscent();

            g2.drawString(hint, Math.max(in.left, x), y);
        } finally {
            g2.dispose();
        }
    }
}
