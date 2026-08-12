package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import org.jspecify.annotations.NonNull;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

/** Modal picker for choosing (and optionally refreshing) a saved Codex chat, with live search-as-you-type. */
final class ChooseCodexThreadDialog extends DialogWrapper {
    private final Project project;
    private final Supplier<CompletableFuture<List<CodexThread>>> refreshSupplier;
    private List<CodexThread> allThreads;
    private final DefaultListModel<CodexThread> matchingThreads = new DefaultListModel<>();
    private final JBList<CodexThread> threadList = new JBList<>(matchingThreads);
    private final JBTextField searchField = new JBTextField();
    private final JButton refreshButton = new JButton("Refresh");
    private String preferredThreadId;

    ChooseCodexThreadDialog(
            Project project,
            List<CodexThread> allThreads,
            String preferredThreadId,
            Supplier<CompletableFuture<List<CodexThread>>> refreshSupplier
    ) {
        super(project);
        this.project = project;
        this.allThreads = allThreads;
        this.preferredThreadId = preferredThreadId;
        this.refreshSupplier = refreshSupplier;
        setTitle("Open Codex Chat");
        threadList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        threadList.setCellRenderer(new ThreadRenderer());
        threadList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && selectedThread() != null) {
                    doOKAction();
                }
            }
        });
        searchField.getDocument().addDocumentListener(new SearchListener());
        refreshButton.addActionListener(ignored -> refreshFromServer());
        refreshMatches();
        init();
    }

    @Override
    protected @NonNull JComponent createCenterPanel() {
        JPanel panel = new JBPanel<>(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(680, 460));
        JPanel searchPanel = new JBPanel<>(new GridLayout(0, 1, 0, 4));
        searchPanel.add(new JBLabel("Search by name or first message:"));
        searchPanel.add(searchField);
        panel.add(searchPanel, BorderLayout.NORTH);
        panel.add(new JBScrollPane(threadList), BorderLayout.CENTER);
        JPanel footer = new JBPanel<>(new BorderLayout());
        footer.add(refreshButton, BorderLayout.WEST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    CodexThread selectedThread() {
        return threadList.getSelectedValue();
    }

    private void refreshMatches() {
        CodexThread previousSelection = threadList.getSelectedValue();
        String selectedThreadId = previousSelection == null ? preferredThreadId : previousSelection.id();
        matchingThreads.clear();
        allThreads.stream()
                .filter(thread -> thread.matchesSearch(searchField.getText()))
                .forEach(matchingThreads::addElement);
        if (selectedThreadId != null && selectById(selectedThreadId)) {
            preferredThreadId = selectedThreadId;
        } else if (!matchingThreads.isEmpty()) {
            threadList.setSelectedIndex(0);
        }
    }

    private boolean selectById(String threadId) {
        for (int index = 0; index < matchingThreads.size(); index++) {
            if (threadId.equals(matchingThreads.get(index).id())) {
                threadList.setSelectedIndex(index);
                return true;
            }
        }
        return false;
    }

    private void refreshFromServer() {
        refreshButton.setEnabled(false);
        refreshButton.setText("Refreshing…");
        refreshSupplier.get().whenComplete((threads, error) -> ApplicationManager.getApplication().invokeLater(() -> {
            refreshButton.setEnabled(true);
            refreshButton.setText("Refresh");
            if (error != null) {
                CodexNotifications.error(project, "Failed to refresh the chat list", CodexUtils.userMessage(error));
                return;
            }
            allThreads = new ArrayList<>(threads);
            refreshMatches();
        }));
    }

    /** Re-filters the visible thread list on every keystroke in the search field. */
    private final class SearchListener implements DocumentListener {
        @Override
        public void insertUpdate(DocumentEvent event) {
            refreshMatches();
        }

        @Override
        public void removeUpdate(DocumentEvent event) {
            refreshMatches();
        }

        @Override
        public void changedUpdate(DocumentEvent event) {
            refreshMatches();
        }
    }

    /** Renders each list cell as the thread's display name instead of its default {@code toString()}. */
    private static final class ThreadRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                javax.swing.JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof CodexThread thread) {
                setText(thread.displayName());
            }
            return this;
        }
    }
}
