package com.ericholub.requestincubator;

import javax.swing.*;
import javax.swing.event.DocumentListener;
import javax.swing.event.DocumentEvent;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;

import java.awt.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.persistence.PersistedObject;


public class RequestIncubatorRequest {

    HttpRequest request;

    // Written by the scheduler thread, read on the EDT.
    public volatile HttpResponse latestResponse = null;

    public RequestIncubatorUI ui = null;

    // Call details
    String host = "";
    String method = "";
    String path = "";
    String nickname = "";

    // Frequency details
    int frequencyTime = 1;
    int frequencyTimeSec = 3600;
    int requestCount = 0;
    String frequencyMethod = "Once in";
    String frequencyUnit = "Hour(s)";

    // Failure Condition details
    public String errorCodeCondition = "Not 2XX";
    public String errorTextCondition = "";

    // Status details
    private JTextArea statusText;

    // Action details
    public volatile boolean isRunning = false;
    private JButton startStopButton;
    private JButton viewResponseButton;
    public volatile boolean statusCodeCheckFailed = false;
    public volatile boolean textCheckFailed = false;

    // Identifier stuff
    public final int id;
    private ScheduledExecutorService scheduler;

    // UI stuff
    public JPanel callCell;
    public JPanel frequencyCell;
    public JPanel optionsCell;
    public JPanel actionsCell;
    public JPanel statusCell;
    public JPanel row;


    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

    public RequestIncubatorRequest(HttpRequest request) {
        this.request = request;
        this.method = request.method();
        this.path = request.path();
        this.id = (int) ID_GENERATOR.getAndIncrement();

        if (request.hasHeader("Host")) {
            this.host = request.headerValue("Host");
        }

        this.callCell = this.createCallCell();
        this.frequencyCell = this.createFrequencyCell();
        this.optionsCell = this.createFailureConditionsCell();
        this.statusCell = this.createStatusCell();
        this.actionsCell = this.createActionsCell();
    }

    public RequestIncubatorRequest(PersistedObject persistedIncubatorRequest) {
        
        this.id = (int) ID_GENERATOR.getAndIncrement();

        this.request = persistedIncubatorRequest.getHttpRequest("request");

        this.host = persistedIncubatorRequest.getString("host");
        this.method = persistedIncubatorRequest.getString("method");
        this.path = persistedIncubatorRequest.getString("path");
        this.nickname = persistedIncubatorRequest.getString("nickname");
        this.frequencyMethod = persistedIncubatorRequest.getString("frequencyMethod");
        this.frequencyUnit = persistedIncubatorRequest.getString("frequencyUnit");
        this.errorCodeCondition = persistedIncubatorRequest.getString("errorCodeCondition");
        this.errorTextCondition = persistedIncubatorRequest.getString("errorTextCondition");
        String latestResponseString = persistedIncubatorRequest.getString("latestResponse");
        if (latestResponseString != null) {
            this.latestResponse = HttpResponse.httpResponse(latestResponseString);
        }

        this.frequencyTime = persistedIncubatorRequest.getInteger("frequencyTime");
        this.frequencyTimeSec = persistedIncubatorRequest.getInteger("frequencyTimeSec");
        this.requestCount = persistedIncubatorRequest.getInteger("requestCount");

        this.statusCodeCheckFailed = persistedIncubatorRequest.getBoolean("statusCodeCheckFailed");
        this.textCheckFailed = persistedIncubatorRequest.getBoolean("textCheckFailed");

        this.callCell = this.createCallCell();
        this.frequencyCell = this.createFrequencyCell();
        this.optionsCell = this.createFailureConditionsCell();
        this.statusCell = this.createStatusCell();
        this.actionsCell = this.createActionsCell();
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RequestIncubatorRequest other = (RequestIncubatorRequest) o;
        return other.id == this.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    /** Must be called on the EDT. */
    public void updateStatusText(String newStatus, boolean isNextActivation, String result) {
        if (isNextActivation) {
            this.statusText.setText(newStatus + "\n\n" + result + "\n\n" + this.nextActivationTime());
        } else {
            this.statusText.setText(newStatus + "\n\n" + result);
        }
    }

    public String callDetails() {
        String details = this.method + " " + this.path;
        int cutoff = 75;
        if (details.length() > cutoff) {
            details = details.substring(0, cutoff) + "...";
        }
        return details;
    }

    public void deleteRequest() {
        this.stopScheduler();
    }

    /** Null-safe: the scheduler does not exist until the first Start. */
    private void stopScheduler() {
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
            this.scheduler = null;
        }
    }

    private void stopRequestTimer() {
        this.stopScheduler();
        this.disableEnableElementsInJPanel(true);
        this.requestCount = 0;
    }

    private void disableEnableElementsInJPanel(boolean enabled) {
        setEnabledRecursive(frequencyCell, enabled);
        setEnabledRecursive(optionsCell, enabled);
    }

    /** Walks nested panels, since some controls live inside child containers. */
    private void setEnabledRecursive(Container container, boolean enabled) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JTextField
                    || comp instanceof JComboBox
                    || comp instanceof JCheckBox) {
                comp.setEnabled(enabled);
            } else if (comp instanceof Container child) {
                setEnabledRecursive(child, enabled);
            }
        }
    }


    // =========================
    // Shared Cell Layout Helpers
    // =========================

    private JPanel createCellPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        return panel;
    }

    private GridBagConstraints cellConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(4, 6, 4, 6);
        return gbc;
    }

    /**
     * Wrapped, read-only text used by the Call and Status cells.
     * Added directly to the cell (no JScrollPane) so revalidate() reaches the row.
     */
    private JTextArea createWrappedText(String text) {
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setColumns(0);
        area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        area.setMinimumSize(new Dimension(0, 0));
        return area;
    }

    // =========================
    // Call Cell
    // =========================

    public JPanel createCallCell() {
        JPanel cell = createCellPanel();

        JTextArea textArea = createWrappedText(
            this.callDetails() + "\nHost: " + this.host
        );

        cell.add(textArea, cellConstraints());

        JTextField nicknameField = new HintTextField(this.nickname, "Nickname");
        nicknameField.setHorizontalAlignment(JTextField.CENTER);;
        nicknameField.setColumns(0);
        nicknameField.setMinimumSize(
            new Dimension(0, nicknameField.getPreferredSize().height)
        );
 
        nicknameField.getDocument().addDocumentListener(
            new DocumentListener() {
                private void update() {
                    RequestIncubatorRequest.this.nickname = nicknameField.getText();
                }
 
                @Override
                public void insertUpdate(DocumentEvent e) {
                    update();
                }
 
                @Override
                public void removeUpdate(DocumentEvent e) {
                    update();
                }
 
                @Override
                public void changedUpdate(DocumentEvent e) {
                    update();
                }
            }
        );
 
        GridBagConstraints inputConstraints = cellConstraints();
        inputConstraints.gridy = 1;
        inputConstraints.weighty = 0;
        inputConstraints.fill = GridBagConstraints.HORIZONTAL;
        inputConstraints.insets = new Insets(2, 6, 4, 6);
        cell.add(nicknameField, inputConstraints);

        return cell;
    }

    // =========================
    // Frequency Cell
    // =========================

    private JPanel createFrequencyCell() {
        JPanel panel = createCellPanel();

        String[] frequencyOptions = {"Once in", "Every"};

        JComboBox<String> frequencyComboBox =
            new JComboBox<>(frequencyOptions);

        frequencyComboBox.setSelectedItem(this.frequencyMethod);

        frequencyComboBox.addActionListener(e -> {
            this.frequencyMethod =
                (String) frequencyComboBox.getSelectedItem();
        });

        JTextField numberField = new JTextField(
            String.valueOf(this.frequencyTime)
        );

        numberField.setColumns(1);
        numberField.setMinimumSize(
            new Dimension(0, numberField.getPreferredSize().height)
        );

        ((AbstractDocument) numberField.getDocument())
            .setDocumentFilter(new NumericFilter());

        numberField.getDocument().addDocumentListener(
            new DocumentListener() {
                private void update() {
                    String text = numberField.getText();

                    if (!text.isEmpty()) {
                        try {
                            RequestIncubatorRequest.this.frequencyTime = Integer.parseInt(text);
                        } catch (NumberFormatException ignored) {
                            // Ignore values outside the integer range.
                        }
                    }
                }

                @Override
                public void insertUpdate(DocumentEvent e) {
                    update();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    update();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    update();
                }
            }
        );

        String[] timeOptions = {
            "Second(s)", "Minute(s)", "Hour(s)"
        };

        JComboBox<String> timeComboBox =
            new JComboBox<>(timeOptions);

        timeComboBox.setSelectedItem(this.frequencyUnit);

        timeComboBox.addActionListener(e -> {
            this.frequencyUnit = (String) timeComboBox.getSelectedItem();
        });

        // Let every control give up width in a narrow column instead of clipping.
        frequencyComboBox.setMinimumSize(
            new Dimension(0, frequencyComboBox.getPreferredSize().height)
        );
        timeComboBox.setMinimumSize(
            new Dimension(0, timeComboBox.getPreferredSize().height)
        );

        JPanel controls = new JPanel(new GridBagLayout());
        controls.setOpaque(false);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(2, 2, 2, 2);

        gbc.gridx = 0;
        gbc.weightx = 0.5;
        controls.add(frequencyComboBox, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        controls.add(numberField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.5;
        controls.add(timeComboBox, gbc);

        GridBagConstraints outer = cellConstraints();
        outer.fill = GridBagConstraints.HORIZONTAL;
        outer.anchor = GridBagConstraints.CENTER;

        panel.add(controls, outer);

        return panel;
    }

    // =========================
    // Failure Conditions Cell
    // =========================

    private JPanel createFailureConditionsCell() {
        JPanel panel = createCellPanel();

        JLabel codeLabel = new JLabel("Code:");
        JLabel textLabel = new JLabel("Text:");

        String[] failureCodeOptions = {
            "3XX", "4XX", "5XX", "Not 2XX", "Ignore"
        };

        JComboBox<String> failureCodeComboBox =
            new JComboBox<>(failureCodeOptions);

        failureCodeComboBox.setSelectedItem(this.errorCodeCondition);

        failureCodeComboBox.addActionListener(e -> {
            this.errorCodeCondition =
                (String) failureCodeComboBox.getSelectedItem();
        });

        failureCodeComboBox.setMinimumSize(
            new Dimension(0, failureCodeComboBox.getPreferredSize().height)
        );

        JTextField failureTextInput = new JTextField(
            this.errorTextCondition == null
                ? ""
                : this.errorTextCondition
        );

        failureTextInput.setColumns(0);
        failureTextInput.setMinimumSize(
            new Dimension(
                0,
                failureTextInput.getPreferredSize().height
            )
        );

        failureTextInput.getDocument().addDocumentListener(
            new DocumentListener() {
                private void update() {
                    errorTextCondition = failureTextInput.getText();
                }

                @Override
                public void insertUpdate(DocumentEvent e) {
                    update();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    update();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    update();
                }
            }
        );

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(codeLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(failureCodeComboBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        panel.add(textLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(failureTextInput, gbc);

        return panel;
    }

    // =========================
    // Status Cell
    // =========================

    private JPanel createStatusCell() {
        JPanel cell = createCellPanel();

        statusText = createWrappedText("Idle");

        if (this.latestResponse != null) {
            String resultText = (this.statusCodeCheckFailed || this.textCheckFailed ? "Call Failed" : "Call Succeeded")
                    + " after " + this.frequencyTime + " " + this.frequencyUnit;
            this.updateStatusText("Finished", false, resultText);
        }

        cell.add(statusText, cellConstraints());

        return cell;
    }

    // =========================
    // Actions Cell
    // =========================

    private JPanel createActionsCell() {
        JPanel panel = createCellPanel();

        startStopButton = new JButton("Start");

        startStopButton.addActionListener(e -> {
            if (!this.isRunning) {
                startRequestTimer();
            } else {
                this.isRunning = false;
                this.startStopButton.setText("Start");
                this.updateStatusText("Stopped", false, "");
                this.updateRowColor("");
                this.stopRequestTimer();
            }
        });

        viewResponseButton = new JButton("Last Response");

        viewResponseButton.addActionListener(e -> {
            showResponseDialog(
                this.ui,
                this.latestResponse
            );
        });

        viewResponseButton.setEnabled(this.latestResponse != null);

        JButton deleteButton = new JButton("Delete");

        deleteButton.addActionListener(e -> {
            this.ui.removeIncubatorRequest(this);
            this.deleteRequest();
        });

        JButton[] buttons = {
            startStopButton,
            viewResponseButton,
            deleteButton
        };

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(2, 4, 2, 4);

        for (int i = 0; i < buttons.length; i++) {
            gbc.gridy = i;
            panel.add(buttons[i], gbc);
        }

        return panel;
    }

    /** Runs on the EDT (from the Start button). */
    private void startRequestTimer() {
        // Interval of zero is rejected by the executor, so clamp to at least 1.
        int amount = Math.max(1, this.frequencyTime);

        // Widen to long before multiplying to avoid int overflow.
        switch (this.frequencyUnit) {
            case "Minute(s)":
                this.frequencyTimeSec = amount * 60;
                break;

            case "Hour(s)":
                this.frequencyTimeSec = amount * 3600;
                break;

            default:
                this.frequencyTimeSec = amount;
        }

        this.isRunning = true;
        this.requestCount = 0;
        this.disableEnableElementsInJPanel(false);
        this.startStopButton.setText("Stop");
        this.updateRowColor("yellow");
        this.updateStatusText("Running", true, "");

        // Make sure an old executor can never linger.
        this.stopScheduler();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        Runnable task = this::fireTimer;

        if (this.frequencyMethod.equals("Once in")) {
            scheduler.schedule(
                task,
                this.frequencyTimeSec,
                TimeUnit.SECONDS
            );
        } else {
            scheduler.scheduleWithFixedDelay(
                task,
                this.frequencyTimeSec,
                this.frequencyTimeSec,
                TimeUnit.SECONDS
            );
        }
    }

    /** Runs on the scheduler thread. Never touches Swing directly. */
    private void fireTimer() {
        boolean callFailed;
        String errorMessage = null;

        try {
            HttpResponse response = this.ui.mainIncubator.makeHTTPRequest(this.request);

            if (response == null) {
                // No response at all (network error, timeout, ...) counts as a failure.
                this.statusCodeCheckFailed = false;
                this.textCheckFailed = false;
                callFailed = true;
                errorMessage = "No response";
            } else {
                this.latestResponse = response;
                callFailed = checkIfCallFailed(response);
                SwingUtilities.invokeLater(() -> this.viewResponseButton.setEnabled(true));
            }
        } catch (Exception ex) {
            // An uncaught exception would silently cancel a repeating schedule.
            this.statusCodeCheckFailed = false;
            this.textCheckFailed = false;
            callFailed = true;
            errorMessage = ex.getClass().getSimpleName()
                + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        }

        final boolean failed = callFailed;
        final String error = errorMessage;
        final boolean once = this.frequencyMethod.equals("Once in");

        SwingUtilities.invokeLater(() -> {
            // The user pressed Stop (or deleted the row) while the call was in flight.
            if (!this.isRunning) {
                return;
            }

            String reason = error == null ? "" : " (" + error + ")";

            if (once) {
                this.isRunning = false;
                this.startStopButton.setText("Start");

                String resultText = (failed ? "Call Failed" : "Call Succeeded")
                    + " after " + this.frequencyTime + " " + this.frequencyUnit
                    + reason;

                this.updateStatusText("Finished", false, resultText);
                this.updateRowColor(failed ? "red" : "green");
                this.stopRequestTimer(); // also shuts the executor down
            }

            else if (failed) {
                this.isRunning = false;
                this.requestCount++;
                this.startStopButton.setText("Start");

                String resultText = "Call Failed after "
                    + ((long) this.frequencyTime * this.requestCount)
                    + " " + this.frequencyUnit + reason;

                this.updateStatusText("Finished", false, resultText);
                this.updateRowColor("red");
                this.stopRequestTimer();
            }

            else {
                this.requestCount++;

                String resultText = "Call Succeeded after "
                    + ((long) this.frequencyTime * this.requestCount)
                    + " " + this.frequencyUnit;

                this.updateStatusText("Running...", true, resultText);
                this.updateRowColor("green");
            }

            this.ui.reloadUI();
        });
    }

    private boolean checkIfCallFailed(HttpResponse response) {
        int code = response.statusCode();
        boolean statusFailed = false;
        boolean textFailed = false;

        switch (this.errorCodeCondition) {
            case "3XX":
                statusFailed = code >= 300 && code < 400;
                break;
            case "4XX":
                statusFailed = code >= 400 && code < 500;
                break;
            case "5XX":
                statusFailed = code >= 500 && code < 600;
                break;
            case "Not 2XX":
                statusFailed = code < 200 || code > 299;
                break;
            default:
                // "Ignore": status code never fails the call.
        }

        String textCondition = this.errorTextCondition;
        if (textCondition != null && !textCondition.isEmpty()) {
            textFailed = response.toString().contains(textCondition);
        }

        // Always assigned, so results from a previous run can't leak into this one.
        this.statusCodeCheckFailed = statusFailed;
        this.textCheckFailed = textFailed;

        return statusFailed || textFailed;
    }

    private String latestResponseToString(HttpResponse response) {
        StringBuilder sb = new StringBuilder();

        // Status line
        sb.append(response.httpVersion())
        .append(" ")
        .append(response.statusCode())
        .append("\r\n");

        // Headers
        response.headers().forEach(header ->
            sb.append(header.name())
            .append(": ")
            .append(header.value())
            .append("\r\n")
        );

        sb.append("\r\n");

        // Body (preserve raw bytes properly)
        byte[] bodyBytes = response.body().getBytes();
        sb.append(new String(bodyBytes, java.nio.charset.StandardCharsets.ISO_8859_1));

        return sb.toString();
    }


    private void showResponseDialog(Component parent, HttpResponse response) {
        if (response == null) {
            return;
        }

        String rawResponse = latestResponseToString(response);
        Window owner = SwingUtilities.getWindowAncestor(parent);

        JDialog dialog = new JDialog(owner, "HTTP Response", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JTextPane textPane = new JTextPane();
        textPane.setText(rawResponse);
        textPane.setEditable(false);
        textPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JScrollPane scrollPane = new JScrollPane(textPane);
        scrollPane.setPreferredSize(new Dimension(1200, 800));

        dialog.getContentPane().add(scrollPane, BorderLayout.CENTER);

        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);

        if (this.textCheckFailed) {
            highlightPattern(textPane, this.errorTextCondition);
        }

        if (this.statusCodeCheckFailed) {
            highlightStatusCode(textPane);
        }
    }

    private void highlightPattern(JTextPane textPane, String pattern) {
        if (pattern == null || pattern.isEmpty()) return;

        Highlighter highlighter = textPane.getHighlighter();
        DefaultHighlighter.DefaultHighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 255, 180));

        Document doc = textPane.getDocument();
        String text;
        try {
            text = doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
            return;
        }

        int index = text.indexOf(pattern);

        while (index > -1) {
            try {
                highlighter.addHighlight(index, index + pattern.length(), painter);
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
            index = text.indexOf(pattern, index + pattern.length());
        }
    }

    private void highlightStatusCode(JTextPane textPane) {
        Highlighter highlighter = textPane.getHighlighter();
        DefaultHighlighter.DefaultHighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 255, 180));

        try {
            // Use the document text so offsets match what the highlighter uses.
            Document doc = textPane.getDocument();
            String text = doc.getText(0, doc.getLength());
            int lineEnd = text.indexOf('\n');

            String firstLine = lineEnd == -1
                    ? text
                    : text.substring(0, lineEnd);

            String[] words = firstLine.split("\\s+");

            if (words.length < 2) {
                return;
            }

            // Status code is the second word, after "HTTP/x.y".
            int start = firstLine.indexOf(words[1], words[0].length());
            int end = start + words[1].length();

            highlighter.addHighlight(start, end, painter);

        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    public void updateRowColor(String color) {
        if (this.row == null) {
            return;
        }

        switch(color) {
            case "yellow":
                this.row.setBackground(new Color(255, 242, 178));
                break;
            case "red":
                this.row.setBackground(new Color(255, 214, 214));
                break;
            case "green":
                this.row.setBackground(new Color(213, 245, 213));
                break;
            default:
                this.row.setBackground(new Color(255, 255, 255));
        }

        this.ui.reloadUI();
    }

    private String nextActivationTime() {
        LocalDateTime nextRun = LocalDateTime.now().plusSeconds(this.frequencyTimeSec);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("h:mm:ss a");

        String nextRunFormatted = nextRun.format(formatter);

        return "Next call: " + nextRunFormatted;
    }

}

