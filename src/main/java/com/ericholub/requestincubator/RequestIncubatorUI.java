package com.ericholub.requestincubator;

import java.util.ArrayList;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

public class RequestIncubatorUI extends JPanel {

    private static final Color HEADER_BACKGROUND = new Color(245, 245, 245);
    private static final Color BORDER_COLOR = new Color(220, 220, 220);
    private static final Color ROW_BORDER_COLOR = new Color(235, 235, 235);
    private static final Insets CELL_INSETS = new Insets(4, 8, 4, 8);

    /** Width assumed before a component has been laid out for the first time. */
    private static final int FALLBACK_WIDTH = 800;

    private static final class Columns {
        public static final double CALL = 0.15;
        public static final double FREQUENCY = 0.30;
        public static final double FAILURE_CONDITIONS = 0.25;
        public static final double STATUS = 0.15;
        public static final double ACTIONS = 0.15;

        private Columns() {}
    }

    /** Order must match the order cells are added to the header and to every row. */
    private static final double[] WEIGHTS = {
        Columns.CALL,
        Columns.FREQUENCY,
        Columns.FAILURE_CONDITIONS,
        Columns.STATUS,
        Columns.ACTIONS
    };

    public final RequestIncubator mainIncubator;

    private final ArrayList<RequestIncubatorRequest> incubatorRequests;
    private final ScrollableRowsPanel rows;
    private final PanelTableHeader header;
    private final JScrollPane scrollPane;

    private int headerRightInset = 0;

    public RequestIncubatorUI(RequestIncubator mainIncubator) {
        this.mainIncubator = mainIncubator;
        this.incubatorRequests = mainIncubator.incubatorRequests;

        setLayout(new BorderLayout());

        rows = new ScrollableRowsPanel();
        header = new PanelTableHeader();

        scrollPane = new JScrollPane(rows);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // Row heights depend on the width (wrapped text), so re-layout when the viewport resizes.
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                rows.revalidate();
                syncHeaderWithScrollbar();
            }
        });

        // The rows lose the scrollbar's width when it appears; the header must lose it too,
        // otherwise header and row columns drift apart by that amount.
        scrollPane.getVerticalScrollBar().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                syncHeaderWithScrollbar();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                syncHeaderWithScrollbar();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                syncHeaderWithScrollbar();
            }
        });

        add(header, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    private void syncHeaderWithScrollbar() {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        int width = bar.isVisible() ? bar.getWidth() : 0;

        if (width == headerRightInset) {
            return;
        }

        headerRightInset = width;
        header.applyBorder(width);
        header.revalidate();
        header.repaint();
    }

    /**
     * Refreshes the table after rows are added or removed or their content changes.
     */
    public void reloadUI() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reloadUI);
            return;
        }

        rows.revalidate();
        rows.repaint();

        revalidate();
        repaint();
    }

    /**
     * Adds a row for every registered request that doesn't have one yet
     * (normally just the most recently registered request).
     */
    public void addIncubatorRequest() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::addIncubatorRequest);
            return;
        }

        for (RequestIncubatorRequest request : new ArrayList<>(incubatorRequests)) {
            if (request.row != null) {
                continue;
            }

            request.ui = this;
            rows.add(new PanelTableRow(request));
        }

        reloadUI();
    }

    /**
     * Removes the specified request and its corresponding UI row.
     */
    public void removeIncubatorRequest(RequestIncubatorRequest request) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> removeIncubatorRequest(request));
            return;
        }

        incubatorRequests.removeIf(existing -> existing.equals(request));

        for (Component component : rows.getComponents()) {
            if (component instanceof PanelTableRow row && row.request.equals(request)) {
                rows.remove(component);
                break;
            }
        }

        reloadUI();
    }

    /**
     * Cleans up all active requests when the extension is unloaded.
     */
    public void unloadUI() {
        for (RequestIncubatorRequest request :
                new ArrayList<>(incubatorRequests)) {
            request.deleteRequest();
        }
    }


    // =========================
    // Layout
    // =========================

    /** Implemented by components whose height depends on the width they are given. */
    private interface HeightForWidth {
        int heightForWidth(int width);
    }

    /**
     * Splits the container into columns using only the weights, never the content sizes.
     * The header and every row use the same instance settings, so their columns line up
     * exactly regardless of what each cell contains.
     */
    private static final class ProportionalColumnsLayout implements LayoutManager {

        private final double[] weights;
        private final Insets cellInsets;
        private final double total;

        ProportionalColumnsLayout(double[] weights, Insets cellInsets) {
            this.weights = weights;
            this.cellInsets = cellInsets;

            double sum = 0;
            for (double weight : weights) {
                sum += weight;
            }
            this.total = sum;
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {}

        @Override
        public void removeLayoutComponent(Component comp) {}

        /** Column boundaries, rounded cumulatively so rounding errors never accumulate. */
        private int[] columnEdges(int availableWidth) {
            int[] edges = new int[weights.length + 1];
            double accumulated = 0;

            for (int i = 0; i < weights.length; i++) {
                accumulated += weights[i];
                edges[i + 1] = (int) Math.round(availableWidth * accumulated / total);
            }

            return edges;
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int width = parent.getWidth() - in.left - in.right;
            int height = parent.getHeight() - in.top - in.bottom;
            int[] edges = columnEdges(Math.max(0, width));

            for (int i = 0; i < parent.getComponentCount() && i < weights.length; i++) {
                parent.getComponent(i).setBounds(
                    in.left + edges[i] + cellInsets.left,
                    in.top + cellInsets.top,
                    Math.max(0, edges[i + 1] - edges[i] - cellInsets.left - cellInsets.right),
                    Math.max(0, height - cellInsets.top - cellInsets.bottom)
                );
            }
        }

        /**
         * Height the container needs when it is {@code width} pixels wide.
         * Wrapped text only reports its real height once it knows its width, so each cell is
         * sized to its column width first (with a tall height so nothing is cut off).
         */
        int heightForWidth(Container parent, int width) {
            Insets in = parent.getInsets();
            int[] edges = columnEdges(Math.max(0, width - in.left - in.right));
            int maxHeight = 0;

            for (int i = 0; i < parent.getComponentCount() && i < weights.length; i++) {
                Component cell = parent.getComponent(i);

                if (!cell.isVisible()) {
                    continue;
                }

                int cellWidth = Math.max(
                    1,
                    edges[i + 1] - edges[i] - cellInsets.left - cellInsets.right
                );

                cell.setSize(cellWidth, Short.MAX_VALUE);
                cell.doLayout();
                cell.invalidate(); // drop the cached preferred size
                maxHeight = Math.max(maxHeight, cell.getPreferredSize().height);
            }

            return maxHeight
                + cellInsets.top + cellInsets.bottom
                + in.top + in.bottom;
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            int width = parent.getWidth() > 0 ? parent.getWidth() : FALLBACK_WIDTH;

            // Width is dictated by the parent, never by the content.
            return new Dimension(0, heightForWidth(parent, width));
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }
    }

    /**
     * Stacks rows vertically at the full width, asking each row for its height at that width.
     */
    private static final class RowsLayout implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component comp) {}

        @Override
        public void removeLayoutComponent(Component comp) {}

        private static int heightOf(Component component, int width) {
            if (component instanceof HeightForWidth sized) {
                return sized.heightForWidth(width);
            }

            return component.getPreferredSize().height;
        }

        /** Inside a scroll pane the viewport's width is the width the rows will get. */
        private static int availableWidth(Container parent) {
            Container grandParent = parent.getParent();
            int width = grandParent instanceof JViewport
                ? grandParent.getWidth()
                : parent.getWidth();

            return width > 0 ? width : FALLBACK_WIDTH;
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int width = parent.getWidth() - in.left - in.right;
            int y = in.top;

            for (Component component : parent.getComponents()) {
                if (!component.isVisible()) {
                    continue;
                }

                int height = heightOf(component, width);
                component.setBounds(in.left, y, width, height);
                y += height;
            }
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int width = availableWidth(parent);
            int innerWidth = width - in.left - in.right;
            int height = in.top + in.bottom;

            for (Component component : parent.getComponents()) {
                if (component.isVisible()) {
                    height += heightOf(component, innerWidth);
                }
            }

            return new Dimension(width, height);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }
    }

    /**
     * Container for the rows that always matches the scroll pane's viewport width.
     */
    private static final class ScrollableRowsPanel extends JPanel implements Scrollable {

        ScrollableRowsPanel() {
            super(new RowsLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(FALLBACK_WIDTH, 300);
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(16, visibleRect.height - 16);
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


    // =========================
    // Header and rows
    // =========================

    /**
     * Shared header panel.
     */
    public class PanelTableHeader extends JPanel {

        public PanelTableHeader() {
            setLayout(new ProportionalColumnsLayout(WEIGHTS, CELL_INSETS));
            setBackground(HEADER_BACKGROUND);
            applyBorder(0);

            add(createHeaderCell("Call"));
            add(createHeaderCell("Frequency"));
            add(createHeaderCell("Failure Conditions"));
            add(createHeaderCell("Status"));
            add(createHeaderCell("Actions"));
        }

        /** Bottom line plus a right inset matching the rows' scrollbar. */
        void applyBorder(int rightInset) {
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(0, 0, 0, rightInset)
            ));
        }

        private JLabel createHeaderCell(String text) {
            JLabel label = new JLabel(text);
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            label.setMinimumSize(new Dimension(0, 0));

            return label;
        }
    }

    /**
     * Individual request row.
     */
    public class PanelTableRow extends JPanel implements HeightForWidth {

        public final RequestIncubatorRequest request;

        private final ProportionalColumnsLayout columns =
            new ProportionalColumnsLayout(WEIGHTS, CELL_INSETS);

        public PanelTableRow(RequestIncubatorRequest request) {
            this.setOpaque(true);
            this.request = request;
            this.request.row = this;

            setLayout(columns);

            setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, ROW_BORDER_COLOR
            ));

            add(request.callCell);
            add(request.frequencyCell);
            add(request.optionsCell);
            add(request.statusCell);
            add(request.actionsCell);

            setAlignmentX(Component.LEFT_ALIGNMENT);

            // If latest request failed
            if (request.statusCodeCheckFailed || request.textCheckFailed) {
                request.updateRowColor("red");
            } 
            
            // If latest request succeeded
            else if (request.latestResponse != null) {
                request.updateRowColor("green");
            }        
        }

        @Override
        public int heightForWidth(int width) {
            return columns.heightForWidth(this, width);
        }
    }
}