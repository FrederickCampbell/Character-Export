package com.dzwnk.exporter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;

/**
 * Generic read-only viewer for every JSON dataset in the active character
 * directory. New exporters automatically become viewable without bespoke UI.
 */
final class CharacterStateViewerPanel extends JPanel
{
    private static final int MAX_VALUES = 25000;
    private static final int MAX_VALUE_LENGTH = 180;

    private final JTextField searchField = new JTextField();
    private final JLabel summaryLabel = new JLabel("No account");
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> valueList = new JList<>(listModel);
    private final AtomicLong generation = new AtomicLong();

    private volatile Path directory;
    private volatile List<String> allLines = Collections.emptyList();

    CharacterStateViewerPanel()
    {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        setBorder(new EmptyBorder(8, 0, 0, 0));

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);

        searchField.setToolTipText("Search every exported account-state value");
        top.add(searchField, BorderLayout.NORTH);

        summaryLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        summaryLabel.setHorizontalAlignment(JLabel.CENTER);
        top.add(summaryLabel, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        valueList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        valueList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        valueList.setForeground(Color.WHITE);
        valueList.setFixedCellHeight(20);

        JScrollPane scroll = new JScrollPane(valueList);
        scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
        scroll.setPreferredSize(new Dimension(0, 330));
        add(scroll, BorderLayout.CENTER);

        searchField.getDocument().addDocumentListener(new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent e)
            {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e)
            {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e)
            {
                applyFilter();
            }
        });
    }

    void setDirectory(Path directory)
    {
        this.directory = directory;
        searchField.setText("");
        refreshAsync();
    }

    void refreshAsync()
    {
        final long expectedGeneration = generation.incrementAndGet();
        final Path expectedDirectory = directory;

        if (expectedDirectory == null)
        {
            allLines = Collections.emptyList();
            SwingUtilities.invokeLater(() ->
            {
                if (generation.get() != expectedGeneration)
                {
                    return;
                }
                listModel.clear();
                summaryLabel.setText("No account");
            });
            return;
        }

        summaryLabel.setText("Refreshing…");

        CompletableFuture
            .supplyAsync(() -> loadLines(expectedDirectory))
            .thenAccept(lines -> SwingUtilities.invokeLater(() ->
            {
                if (generation.get() != expectedGeneration ||
                    !expectedDirectory.equals(directory))
                {
                    return;
                }

                allLines = lines;
                applyFilter();
            }));
    }

    private List<String> loadLines(Path accountDir)
    {
        List<String> lines = new ArrayList<>();

        try
        {
            if (!Files.isDirectory(accountDir))
            {
                return lines;
            }

            List<Path> files = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.list(accountDir))
            {
                stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .forEach(files::add);
            }

            for (Path file : files)
            {
                if (lines.size() >= MAX_VALUES)
                {
                    break;
                }

                try
                {
                    String raw = new String(
                        Files.readAllBytes(file),
                        StandardCharsets.UTF_8
                    );
                    JsonElement root = new JsonParser().parse(raw);
                    flatten(
                        file.getFileName().toString(),
                        root,
                        lines
                    );
                }
                catch (Exception ex)
                {
                    lines.add(
                        file.getFileName() +
                        " › [read error] = " +
                        ex.getClass().getSimpleName()
                    );
                }
            }
        }
        catch (Exception ignored)
        {
            // The viewer is optional UI. Exporting must never depend on it.
        }

        return lines;
    }

    private void flatten(
        String path,
        JsonElement element,
        List<String> lines)
    {
        if (lines.size() >= MAX_VALUES || element == null || element.isJsonNull())
        {
            return;
        }

        if (element.isJsonObject())
        {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet())
            {
                flatten(
                    path + " › " + entry.getKey(),
                    entry.getValue(),
                    lines
                );
                if (lines.size() >= MAX_VALUES)
                {
                    return;
                }
            }
            return;
        }

        if (element.isJsonArray())
        {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++)
            {
                flatten(
                    path + " › [" + i + "]",
                    array.get(i),
                    lines
                );
                if (lines.size() >= MAX_VALUES)
                {
                    return;
                }
            }
            return;
        }

        String value;
        try
        {
            value = element.getAsString();
        }
        catch (RuntimeException ex)
        {
            value = element.toString();
        }

        if (value.length() > MAX_VALUE_LENGTH)
        {
            value = value.substring(0, MAX_VALUE_LENGTH - 1) + "…";
        }

        lines.add(path + " = " + value);
    }

    private void applyFilter()
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(this::applyFilter);
            return;
        }

        String query = searchField.getText() == null
            ? ""
            : searchField.getText().trim().toLowerCase(Locale.ROOT);

        listModel.clear();
        int matched = 0;

        for (String line : allLines)
        {
            if (query.isEmpty() ||
                line.toLowerCase(Locale.ROOT).contains(query))
            {
                listModel.addElement(line);
                matched++;
            }
        }

        String suffix = allLines.size() >= MAX_VALUES ? " (capped)" : "";
        summaryLabel.setText(
            matched + " shown · " +
            allLines.size() + " values" +
            suffix
        );
    }
}
