/*
 * Copyright (c) 2026, DZWNK
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.dzwnk.exporter;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Compact RuneLite-native status panel for Character Export.
 *
 * All dataset metadata and freshness rules come from ExportDataset and
 * DatasetFreshness; the panel does not maintain its own parallel policy table.
 */
@Slf4j
class CharacterStateExporterPanel extends PluginPanel
{
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("h:mm:ss a");
    private static final DateTimeFormatter DATE_TIME_FMT =
        DateTimeFormatter.ofPattern("MMM d, h:mm:ss a");

    private static final int BUTTON_HEIGHT = 30;
    private static final int STATUS_ROW_HEIGHT = 31;
    private static final Color STALE_COLOR =
        new Color(255, 193, 7);
    private static final Color ERROR_COLOR =
        new Color(196, 64, 64);

    enum SyncOutcome
    {
        UPDATED,
        UP_TO_DATE,
        UNAVAILABLE,
        FAILED
    }

    private static final class StatusRow
    {
        private final JLabel primary;
        private final JLabel detail;

        private StatusRow(
            JLabel primary,
            JLabel detail)
        {
            this.primary = primary;
            this.detail = detail;
        }
    }

    private final Gson gson;
    private final String sessionId;
    private final Map<ExportDataset, StatusRow> statusRows =
        new EnumMap<>(ExportDataset.class);
    private final Map<String, SyncOutcome> manualSyncOutcomes =
        new LinkedHashMap<>();
    private final AtomicLong accountViewGeneration =
        new AtomicLong();

    private final JLabel accountLabel;
    private final JLabel interactionNoteLabel;
    private final CharacterStateViewerPanel viewerPanel;
    private final JButton refreshButton;
    private final Timer freshnessTimer;

    private final Object manualSyncLock = new Object();
    private volatile Path currentOutputDir;
    private volatile String renderedAccountName;
    private volatile Path renderedOutputDir;
    private boolean manualSyncInProgress;

    CharacterStateExporterPanel(
        Runnable exportAllAction,
        String buildLabel,
        Gson gson,
        String sessionId)
    {
        super(false);
        this.gson = gson;
        this.sessionId = sessionId;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel title = centeredLabel("Character Export");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(Color.WHITE);
        add(title);

        JLabel version = centeredLabel(buildLabel);
        version.setFont(FontManager.getRunescapeSmallFont());
        version.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        version.setBorder(new EmptyBorder(0, 0, 5, 0));
        add(version);

        accountLabel = centeredLabel("Not logged in");
        accountLabel.setFont(FontManager.getRunescapeBoldFont());
        accountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        accountLabel.setBorder(new EmptyBorder(0, 0, 2, 0));
        add(accountLabel);

        JLabel publicSurface = centeredLabel("13 public JSON snapshots");
        publicSurface.setFont(FontManager.getRunescapeSmallFont());
        publicSurface.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        publicSurface.setToolTipText(
            "Character Export publishes 13 account-root JSON files."
        );
        publicSurface.setBorder(new EmptyBorder(0, 0, 5, 0));
        add(publicSurface);

        JPanel statusGrid = new JPanel();
        statusGrid.setLayout(
            new BoxLayout(statusGrid, BoxLayout.Y_AXIS)
        );
        statusGrid.setOpaque(false);
        statusGrid.setBorder(new EmptyBorder(0, 0, 0, 0));

        java.util.List<ExportDataset> datasets =
            ExportDataset.panelDatasets();
        for (int i = 0; i < datasets.size(); i++)
        {
            ExportDataset dataset = datasets.get(i);
            statusGrid.add(createStatusRow(dataset));
            if (i < datasets.size() - 1)
            {
                statusGrid.add(
                    Box.createRigidArea(new Dimension(0, 1))
                );
            }
        }

        statusGrid.setAlignmentX(CENTER_ALIGNMENT);
        statusGrid.setMaximumSize(
            new Dimension(
                Integer.MAX_VALUE,
                datasets.size() * (STATUS_ROW_HEIGHT + 1) - 1
            )
        );
        add(statusGrid);
        add(Box.createRigidArea(new Dimension(0, 8)));

        interactionNoteLabel = new JLabel();
        interactionNoteLabel.setFont(
            FontManager.getRunescapeSmallFont()
        );
        interactionNoteLabel.setHorizontalAlignment(
            JLabel.CENTER
        );
        interactionNoteLabel.setAlignmentX(CENTER_ALIGNMENT);
        interactionNoteLabel.setOpaque(true);
        interactionNoteLabel.setBackground(
            ColorScheme.DARKER_GRAY_COLOR
        );
        interactionNoteLabel.setForeground(
            ColorScheme.LIGHT_GRAY_COLOR
        );
        interactionNoteLabel.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                    1,
                    0,
                    1,
                    0,
                    ColorScheme.MEDIUM_GRAY_COLOR
                ),
                new EmptyBorder(7, 8, 7, 8)
            )
        );
        interactionNoteLabel.setMaximumSize(
            new Dimension(Integer.MAX_VALUE, 52)
        );
        add(interactionNoteLabel);
        add(Box.createRigidArea(new Dimension(0, 8)));

        refreshButton = createButton("Refresh Now");
        refreshButton.setToolTipText(
            "Force an immediate snapshot of everything RuneLite can currently observe."
        );
        refreshButton.addActionListener(
            event -> exportAllAction.run()
        );
        add(refreshButton);
        add(Box.createRigidArea(new Dimension(0, 6)));

        JButton openFolderButton = createButton("Open Folder");
        openFolderButton.setToolTipText(
            "Open this character's export folder."
        );
        openFolderButton.addActionListener(
            event -> openOutputFolder()
        );
        add(openFolderButton);
        add(Box.createRigidArea(new Dimension(0, 6)));

        viewerPanel = new CharacterStateViewerPanel();
        viewerPanel.setVisible(false);
        viewerPanel.setAlignmentX(CENTER_ALIGNMENT);
        viewerPanel.setMaximumSize(
            new Dimension(Integer.MAX_VALUE, 430)
        );

        JButton viewerButton = createButton("Browse JSON");
        viewerButton.setToolTipText(
            "Search all 13 public account-root JSON files."
        );
        viewerButton.addActionListener(event ->
        {
            boolean show = !viewerPanel.isVisible();
            viewerPanel.setVisible(show);
            viewerButton.setText(
                show ? "Hide JSON" : "Browse JSON"
            );

            if (show)
            {
                viewerPanel.refreshAsync();
            }

            revalidate();
            repaint();
        });
        add(viewerButton);
        add(viewerPanel);

        freshnessTimer = new Timer(
            60_000,
            event -> refreshInteractiveFreshnessFromDisk()
        );
        freshnessTimer.setInitialDelay(60_000);
        freshnessTimer.setRepeats(true);
        freshnessTimer.start();

        refreshInteractionNote();
    }

    private JPanel createStatusRow(ExportDataset dataset)
    {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(true);
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                    0,
                    0,
                    1,
                    0,
                    ColorScheme.DARK_GRAY_COLOR
                ),
                new EmptyBorder(3, 7, 3, 7)
            )
        );
        row.setPreferredSize(new Dimension(0, STATUS_ROW_HEIGHT));
        row.setMaximumSize(
            new Dimension(Integer.MAX_VALUE, STATUS_ROW_HEIGHT)
        );

        JLabel name = new JLabel(dataset.label());
        name.setFont(FontManager.getRunescapeSmallFont());
        name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        name.setPreferredSize(new Dimension(102, STATUS_ROW_HEIGHT - 2));
        if (dataset.publicFileName() != null)
        {
            name.setToolTipText(dataset.publicFileName());
            row.setToolTipText(dataset.publicFileName());
        }
        row.add(name, BorderLayout.WEST);

        JPanel status = new JPanel();
        status.setOpaque(false);
        status.setLayout(
            new BoxLayout(status, BoxLayout.Y_AXIS)
        );

        JLabel primary = centeredLabel("Log in");
        primary.setFont(
            FontManager.getRunescapeSmallFont().deriveFont(
                java.awt.Font.BOLD
            )
        );
        primary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        status.add(primary);

        JLabel detail = centeredLabel(" ");
        detail.setFont(FontManager.getRunescapeSmallFont());
        detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        status.add(detail);

        row.add(status, BorderLayout.CENTER);
        statusRows.put(
            dataset,
            new StatusRow(primary, detail)
        );
        return row;
    }

    private static JLabel centeredLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setHorizontalAlignment(JLabel.CENTER);
        label.setAlignmentX(CENTER_ALIGNMENT);
        label.setMaximumSize(
            new Dimension(Integer.MAX_VALUE, 24)
        );
        return label;
    }

    private static JButton createButton(String text)
    {
        JButton button = new JButton(text);
        button.setAlignmentX(CENTER_ALIGNMENT);
        button.setFocusPainted(false);
        button.setFont(FontManager.getRunescapeSmallFont());
        button.setForeground(Color.WHITE);
        button.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        button.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    ColorScheme.DARKER_GRAY_COLOR
                ),
                new EmptyBorder(5, 8, 5, 8)
            )
        );
        button.setMaximumSize(
            new Dimension(Integer.MAX_VALUE, BUTTON_HEIGHT)
        );
        button.setPreferredSize(
            new Dimension(170, BUTTON_HEIGHT)
        );
        return button;
    }

    void setAccount(String playerName, Path outputDir)
    {
        if (playerName == null ||
            playerName.trim().isEmpty() ||
            outputDir == null)
        {
            return;
        }

        currentOutputDir = outputDir;
        final long generation =
            accountViewGeneration.incrementAndGet();

        runOnEdt(() ->
        {
            if (generation != accountViewGeneration.get())
            {
                return;
            }

            renderedAccountName = playerName;
            renderedOutputDir = outputDir;
            accountLabel.setText(playerName);
            accountLabel.setForeground(Color.WHITE);
            viewerPanel.setDirectory(outputDir);
            resetDatasetRowsForAccount();
            refreshFromDisk();
        });
    }

    void refreshAccountIdentity(
        String playerName,
        Path outputDir)
    {
        if (playerName == null ||
            playerName.trim().isEmpty() ||
            outputDir == null)
        {
            return;
        }

        currentOutputDir = outputDir;
        if (playerName.equals(renderedAccountName) &&
            outputDir.equals(renderedOutputDir))
        {
            return;
        }

        setAccount(playerName, outputDir);
    }

    void shutdown()
    {
        freshnessTimer.stop();
        viewerPanel.setDirectory(null);
    }

    void clearAccount()
    {
        currentOutputDir = null;
        final long generation =
            accountViewGeneration.incrementAndGet();

        runOnEdt(() ->
        {
            if (generation != accountViewGeneration.get())
            {
                return;
            }

            renderedAccountName = null;
            renderedOutputDir = null;
            accountLabel.setText("Not logged in");
            accountLabel.setForeground(
                ColorScheme.LIGHT_GRAY_COLOR
            );
            viewerPanel.setDirectory(null);

            synchronized (manualSyncLock)
            {
                manualSyncInProgress = false;
                manualSyncOutcomes.clear();
            }

            refreshButton.setEnabled(true);
            refreshButton.setText("Refresh Now");

            for (ExportDataset dataset :
                ExportDataset.values())
            {
                setRowState(
                    dataset,
                    "Log in",
                    " ",
                    ColorScheme.LIGHT_GRAY_COLOR,
                    null
                );
            }

            refreshInteractionNote();
        });
    }

    private void resetDatasetRowsForAccount()
    {
        for (ExportDataset dataset : ExportDataset.values())
        {
            setRowState(
                dataset,
                dataset.interactionBacked()
                    ? "No snapshot"
                    : dataset.readyHint(),
                " ",
                ColorScheme.LIGHT_GRAY_COLOR,
                dataset.interactionBacked()
                    ? dataset.refreshAction()
                    : null
            );
        }
    }

    void beginManualSync()
    {
        synchronized (manualSyncLock)
        {
            manualSyncInProgress = true;
            manualSyncOutcomes.clear();
        }

        runOnEdt(() ->
        {
            refreshButton.setEnabled(false);
            refreshButton.setText("Refreshing…");
        });
    }

    void markExported(String datasetKey)
    {
        ExportDataset dataset =
            ExportDataset.fromKey(datasetKey);
        if (dataset == null)
        {
            return;
        }

        runOnEdt(() ->
        {
            renderDatasetFromDisk(
                dataset,
                SyncOutcome.UPDATED
            );
            if (viewerPanel.isVisible())
            {
                viewerPanel.refreshAsync();
            }
            refreshInteractionNote();
        });

        recordManualOutcome(
            dataset,
            SyncOutcome.UPDATED
        );
    }

    void markChecked(String datasetKey)
    {
        ExportDataset dataset =
            ExportDataset.fromKey(datasetKey);
        if (dataset == null)
        {
            return;
        }

        runOnEdt(() ->
        {
            renderDatasetFromDisk(
                dataset,
                SyncOutcome.UP_TO_DATE
            );
            refreshInteractionNote();
        });

        recordManualOutcome(
            dataset,
            SyncOutcome.UP_TO_DATE
        );
    }

    void markUnavailable(
        String datasetKey,
        String detail,
        String tooltip)
    {
        ExportDataset dataset =
            ExportDataset.fromKey(datasetKey);
        if (dataset == null)
        {
            return;
        }

        runOnEdt(() ->
        {
            if (hasDatasetFile(dataset))
            {
                StatusRow row = statusRows.get(dataset);
                if (row != null)
                {
                    row.primary.setToolTipText(tooltip);
                    row.detail.setToolTipText(tooltip);
                }
            }
            else
            {
                setRowState(
                    dataset,
                    "Not available",
                    detail == null ? "Unavailable" : detail,
                    ColorScheme.LIGHT_GRAY_COLOR,
                    tooltip
                );
            }
        });

        recordManualOutcome(
            dataset,
            SyncOutcome.UNAVAILABLE
        );
    }

    void skipDatasetInSync(String datasetKey)
    {
        ExportDataset dataset =
            ExportDataset.fromKey(datasetKey);
        if (dataset != null)
        {
            recordManualOutcome(
                dataset,
                SyncOutcome.UNAVAILABLE
            );
        }
    }

    void markFailed(String datasetKey, String tooltip)
    {
        ExportDataset dataset =
            ExportDataset.fromKey(datasetKey);
        if (dataset == null)
        {
            return;
        }

        runOnEdt(() -> setRowState(
            dataset,
            "Failed",
            "Try again",
            ERROR_COLOR,
            tooltip
        ));

        recordManualOutcome(dataset, SyncOutcome.FAILED);
    }

    void restoreFromDisk(Path accountDir)
    {
        if (accountDir == null ||
            !Files.isDirectory(accountDir))
        {
            return;
        }

        currentOutputDir = accountDir;
        runOnEdt(this::refreshFromDisk);
    }

    private void refreshFromDisk()
    {
        Path dir = currentOutputDir;
        if (dir == null || !Files.isDirectory(dir))
        {
            refreshInteractionNote();
            return;
        }

        for (ExportDataset dataset :
            ExportDataset.values())
        {
            if (hasDatasetFile(dataset))
            {
                renderDatasetFromDisk(dataset, null);
            }
            else if (dataset.interactionBacked())
            {
                setRowState(
                    dataset,
                    "No snapshot",
                    dataset.readyHint(),
                    STALE_COLOR,
                    dataset.refreshAction()
                );
            }
        }

        refreshInteractionNote();
    }

    private void renderDatasetFromDisk(
        ExportDataset dataset,
        SyncOutcome outcome)
    {
        Path dir = currentOutputDir;
        if (dir == null)
        {
            return;
        }

        Path fragmentPath =
            ExportLayout.datasetPath(dir, dataset);
        Map<String, Object> fragment =
            UnifiedCharacterSnapshot.readFragment(
                dir,
                gson,
                dataset
            );

        DatasetFreshness.Observation observation =
            DatasetFreshness.evaluate(
                dataset,
                fragment,
                fragmentPath,
                sessionId
            );

        if (dataset.interactionBacked())
        {
            renderInteractiveDataset(dataset, observation);
            return;
        }

        Instant observedAt = observation.observedAt();
        if (observedAt == null)
        {
            Path panelPath =
                ExportLayout.panelPath(dir, dataset);
            try
            {
                if (Files.isRegularFile(panelPath))
                {
                    observedAt =
                        Files.getLastModifiedTime(panelPath).toInstant();
                }
            }
            catch (IOException ignored)
            {
            }
        }

        if (observedAt == null)
        {
            setRowState(
                dataset,
                dataset.readyHint(),
                " ",
                ColorScheme.LIGHT_GRAY_COLOR,
                null
            );
            return;
        }

        String detail;
        if (outcome == SyncOutcome.UPDATED)
        {
            detail = "Updated";
        }
        else if (outcome == SyncOutcome.UP_TO_DATE)
        {
            detail = "Up to date";
        }
        else if (observation.currentSession())
        {
            detail = "Current";
        }
        else
        {
            detail = "Saved";
        }

        setRowState(
            dataset,
            formatStatusTime(observedAt),
            detail,
            ColorScheme.PROGRESS_COMPLETE_COLOR,
            formatStatusTooltip(observedAt, detail)
        );
    }

    private void renderInteractiveDataset(
        ExportDataset dataset,
        DatasetFreshness.Observation observation)
    {
        Instant observedAt = observation.observedAt();

        switch (observation.status())
        {
            case CURRENT:
                setRowState(
                    dataset,
                    observedAt == null
                        ? "Current"
                        : formatStatusTime(observedAt),
                    "Current",
                    ColorScheme.PROGRESS_COMPLETE_COLOR,
                    observedAt == null
                        ? dataset.refreshAction()
                        : formatInteractiveTooltip(
                            dataset,
                            observedAt
                        )
                );
                return;

            case STALE:
                setRowState(
                    dataset,
                    observedAt == null
                        ? "Stale"
                        : formatRelativeAge(observedAt),
                    "Stale",
                    STALE_COLOR,
                    observedAt == null
                        ? dataset.refreshAction()
                        : formatInteractiveTooltip(
                            dataset,
                            observedAt
                        )
                );
                return;

            case SAVED:
                setRowState(
                    dataset,
                    observedAt == null
                        ? "Saved"
                        : formatStatusTime(observedAt),
                    dataset == ExportDataset.COLLECTION_LOG
                        ? "Saved"
                        : dataset.readyHint(),
                    ColorScheme.PROGRESS_COMPLETE_COLOR,
                    observedAt == null
                        ? dataset.refreshAction()
                        : formatInteractiveTooltip(
                            dataset,
                            observedAt
                        )
                );
                return;

            case UNKNOWN:
            default:
                setRowState(
                    dataset,
                    "No snapshot",
                    dataset.readyHint(),
                    STALE_COLOR,
                    dataset.refreshAction()
                );
        }
    }

    private void refreshInteractiveFreshnessFromDisk()
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(
                this::refreshInteractiveFreshnessFromDisk
            );
            return;
        }

        for (ExportDataset dataset :
            ExportDataset.values())
        {
            if (dataset.interactionBacked() &&
                hasDatasetFile(dataset))
            {
                renderDatasetFromDisk(dataset, null);
            }
        }

        refreshInteractionNote();
    }

    private void refreshInteractionNote()
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(this::refreshInteractionNote);
            return;
        }

        Path dir = currentOutputDir;
        if (dir == null)
        {
            interactionNoteLabel.setText(
                "<html><b>Interactive data:</b><br>" +
                    "Log in to check refresh needs.</html>"
            );
            interactionNoteLabel.setForeground(
                ColorScheme.LIGHT_GRAY_COLOR
            );
            return;
        }

        java.util.List<String> pending =
            new java.util.ArrayList<>();

        for (ExportDataset dataset :
            ExportDataset.values())
        {
            if (!dataset.interactionBacked())
            {
                continue;
            }

            Map<String, Object> fragment =
                UnifiedCharacterSnapshot.readFragment(
                    dir,
                    gson,
                    dataset
                );

            DatasetFreshness.Observation observation =
                DatasetFreshness.evaluate(
                    dataset,
                    fragment,
                    ExportLayout.datasetPath(dir, dataset),
                    sessionId
                );

            boolean needsRefresh =
                dataset == ExportDataset.COLLECTION_LOG
                    ? observation.status() ==
                        DatasetFreshness.Status.UNKNOWN
                    : !observation.currentSession();

            if (needsRefresh)
            {
                pending.add(dataset.label());
            }
        }

        if (pending.isEmpty())
        {
            interactionNoteLabel.setText(
                "<html><b>Interactive data:</b><br>" +
                    "No refresh needed</html>"
            );
            interactionNoteLabel.setForeground(
                ColorScheme.PROGRESS_COMPLETE_COLOR
            );
            return;
        }

        interactionNoteLabel.setText(
            "<html><b>Open in game to refresh:</b><br>" +
                String.join(" · ", pending) +
                "</html>"
        );
        interactionNoteLabel.setForeground(STALE_COLOR);
    }

    private boolean hasDatasetFile(ExportDataset dataset)
    {
        Path dir = currentOutputDir;
        if (dir == null)
        {
            return false;
        }

        return Files.isRegularFile(
            ExportLayout.panelPath(dir, dataset)
        ) ||
            Files.isRegularFile(
                ExportLayout.datasetPath(dir, dataset)
            );
    }

    private void recordManualOutcome(
        ExportDataset dataset,
        SyncOutcome outcome)
    {
        boolean complete = false;

        synchronized (manualSyncLock)
        {
            if (!manualSyncInProgress)
            {
                return;
            }

            manualSyncOutcomes.put(dataset.key(), outcome);
            if (manualSyncOutcomes.size() >=
                ExportDataset.values().length)
            {
                manualSyncInProgress = false;
                complete = true;
            }
        }

        if (complete)
        {
            runOnEdt(() ->
            {
                refreshButton.setText("Refresh Now");
                refreshButton.setEnabled(true);
            });
        }
    }

    private void setRowState(
        ExportDataset dataset,
        String primary,
        String detail,
        Color color,
        String tooltip)
    {
        StatusRow row = statusRows.get(dataset);
        if (row == null)
        {
            return;
        }

        row.primary.setText(primary);
        row.primary.setForeground(color);
        row.primary.setToolTipText(tooltip);

        row.detail.setText(detail);
        row.detail.setForeground(color);
        row.detail.setToolTipText(tooltip);
    }

    private void openOutputFolder()
    {
        Path dir = currentOutputDir;
        if (dir == null || !Files.isDirectory(dir))
        {
            return;
        }

        try
        {
            Desktop.getDesktop().open(dir.toFile());
        }
        catch (IOException |
               UnsupportedOperationException ex)
        {
            log.debug(
                "Could not open output folder: {}",
                ex.toString()
            );
        }
    }

    private static void runOnEdt(Runnable action)
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            action.run();
        }
        else
        {
            SwingUtilities.invokeLater(action);
        }
    }

    static String formatStatusTime(Instant instant)
    {
        ZonedDateTime local =
            instant.atZone(ZoneId.systemDefault());
        return local.toLocalTime().format(TIME_FMT);
    }

    static String formatStatusTooltip(
        Instant instant,
        String prefix)
    {
        ZonedDateTime local =
            instant.atZone(ZoneId.systemDefault());
        return prefix + " " + local.format(DATE_TIME_FMT);
    }

    static String formatRelativeAge(Instant instant)
    {
        long minutes = Math.max(
            0L,
            Duration.between(
                instant,
                Instant.now()
            ).toMinutes()
        );

        if (minutes < 60L)
        {
            return minutes + "m ago";
        }

        long hours = minutes / 60L;
        if (hours < 48L)
        {
            return hours + "h ago";
        }

        long days = hours / 24L;
        long remainingHours = hours % 24L;
        if (days < 7L && remainingHours > 0L)
        {
            return days + "d " +
                remainingHours + "h ago";
        }

        return days + "d ago";
    }

    private static String formatDetailedAge(Instant instant)
    {
        long minutes = Math.max(
            0L,
            Duration.between(
                instant,
                Instant.now()
            ).toMinutes()
        );

        long days = minutes / (24L * 60L);
        long hours = (minutes / 60L) % 24L;
        long remainingMinutes = minutes % 60L;

        if (days > 0L)
        {
            return days + "d " +
                hours + "h " +
                remainingMinutes + "m";
        }

        if (hours > 0L)
        {
            return hours + "h " +
                remainingMinutes + "m";
        }

        return remainingMinutes + "m";
    }

    private static String formatInteractiveTooltip(
        ExportDataset dataset,
        Instant instant)
    {
        ZonedDateTime local =
            instant.atZone(ZoneId.systemDefault());

        String instruction =
            dataset.refreshAction() == null
                ? "Expose this data in-game to refresh."
                : dataset.refreshAction() + " to refresh.";

        return "<html>" +
            "Last observed: " +
            local.format(DATE_TIME_FMT) +
            "<br>Age: " +
            formatDetailedAge(instant) +
            "<br>" +
            instruction +
            "</html>";
    }
}
