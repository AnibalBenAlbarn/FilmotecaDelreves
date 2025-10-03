package org.example.filmotecadelreves.UI;

import javafx.collections.ObservableList;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Utility helpers to enhance {@link TableView} interactions.
 */
public final class TableUtils {

    private TableUtils() {
        // Utility class
    }

    /**
     * Enables clipboard copy support (Ctrl/Cmd + C) for the provided table and optionally
     * handles paste operations when the user presses Ctrl/Cmd + V.
     *
     * @param table        table view to enhance
     * @param pasteHandler optional handler that receives the clipboard text on paste
     *                     operations. If {@code null}, paste is ignored.
     * @param <T>          table item type
     */
    public static <T> void enableCopyPasteSupport(TableView<T> table, Consumer<String> pasteHandler) {
        table.getSelectionModel().setCellSelectionEnabled(true);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        table.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.C && event.isShortcutDown()) {
                copySelectionToClipboard(table);
                event.consume();
            } else if (event.getCode() == KeyCode.V && event.isShortcutDown() && pasteHandler != null) {
                String clipboardText = getClipboardString();
                if (clipboardText != null) {
                    pasteHandler.accept(clipboardText);
                }
                event.consume();
            }
        });
    }

    /**
     * Creates a context menu with copy/paste options bound to the supplied table.
     *
     * @param table        table view whose selection should be copied
     * @param pasteHandler optional handler invoked with the clipboard string when the user
     *                     chooses the paste option. If {@code null}, the paste item will be
     *                     disabled.
     * @param <T>          table item type
     * @return configured context menu
     */
    public static <T> ContextMenu createCopyPasteContextMenu(TableView<T> table, Consumer<String> pasteHandler) {
        MenuItem copyItem = new MenuItem("Copiar");
        copyItem.setOnAction(event -> copySelectionToClipboard(table));

        MenuItem pasteItem = new MenuItem("Pegar");
        if (pasteHandler != null) {
            pasteItem.setOnAction(event -> {
                String clipboardText = getClipboardString();
                if (clipboardText != null) {
                    pasteHandler.accept(clipboardText);
                }
            });
        } else {
            pasteItem.setDisable(true);
        }

        ContextMenu contextMenu = new ContextMenu();
        contextMenu.getItems().addAll(copyItem, pasteItem);
        return contextMenu;
    }

    /**
     * Copies the currently selected cells from a table view to the system clipboard.
     *
     * @param table table view whose selected cell values will be copied
     * @param <T>   table item type
     */
    public static <T> void copySelectionToClipboard(TableView<T> table) {
        ObservableList<TablePosition> selectedCells = table.getSelectionModel().getSelectedCells();
        if (selectedCells.isEmpty()) {
            return;
        }

        List<TablePosition> positions = new ArrayList<>(selectedCells);
        positions.sort(Comparator
                .comparingInt(TablePosition::getRow)
                .thenComparingInt(TablePosition::getColumn));

        StringBuilder clipboardContent = new StringBuilder();
        int previousRow = -1;

        for (TablePosition position : positions) {
            if (previousRow == position.getRow()) {
                clipboardContent.append('\t');
            } else if (previousRow != -1) {
                clipboardContent.append('\n');
            }

            TableColumn<?, ?> column = table.getColumns().get(position.getColumn());
            Object cellData = column.getCellData(position.getRow());
            clipboardContent.append(cellData != null ? cellData.toString() : "");

            previousRow = position.getRow();
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(clipboardContent.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * Ensures the given row becomes selected when the user performs a right click on it.
     * This improves the behaviour of context menus that depend on the selected item.
     *
     * @param table table view that owns the row
     * @param row   table row that should react to right clicks
     * @param <T>   table item type
     */
    public static <T> void installRowSelectionOnRightClick(TableView<T> table, TableRow<T> row) {
        row.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.SECONDARY && !row.isEmpty()) {
                table.getSelectionModel().clearAndSelect(row.getIndex());
            }
        });
    }

    private static String getClipboardString() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) {
            return clipboard.getString();
        }
        return null;
    }
}
