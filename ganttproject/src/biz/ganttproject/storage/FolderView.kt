// Copyright (C) 2017 BarD Software
// Author: dbarashev@bardsoftware.com
//
// A set of classes for showing filesystem-like hierarchy as a list view and breadcrumb bar.
// Shown elements are expected to implement interface FolderItem.
//
// FolderView class encapsulates a list representing the contents of a single folder.
package biz.ganttproject.storage

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView
import javafx.beans.Observable
import javafx.beans.property.BooleanProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.util.Callback
import net.sourceforge.ganttproject.document.webdav.WebDavResource
import org.controlsfx.control.BreadCrumbBar
import java.nio.file.Path
import java.util.*
import java.util.function.Consumer

/**
 * Interface of a single filesystem item.
 */
interface FolderItem {
  // Is this item locked?
  val isLocked: Boolean
  // Is it possible to acquire exclusive lock on this item?
  val isLockable: Boolean
  // Item name
  val name: String
  // Is it a directory?
  val isDirectory: Boolean
}

/**
 * Encapsulates a list view showing the contents of a single folder.
 */
class FolderView<T: FolderItem>(val myDialogUi: StorageDialogBuilder.DialogUi,
                 onDeleteResource: Runnable,
                 onToggleLockResource: Runnable,
                 isLockingSupported: BooleanProperty) {

  var myContents: ObservableList<T>? = null
  val listView: ListView<ListViewItem<T>> = ListView()
  init {
    listView.setCellFactory { _ ->
      createListCell(myDialogUi, onDeleteResource, onToggleLockResource, isLockingSupported)
    }
    listView.selectionModel.selectedItemProperty().addListener { _, oldValue, newValue ->
      if (oldValue != null) {
        oldValue.isSelected.value = false
      }
      if (newValue != null) {
        newValue.isSelected.value = true
      }
    }
  }

  /**
   * Loads the list of folder contents into the view.
   */
  fun setResources(folderContents: ObservableList<T>) {
    myContents = folderContents
    reloadItems(folderContents)
  }

  private fun reloadItems(folderContents: ObservableList<T>) {
    val items = FXCollections.observableArrayList(createExtractor<T>())
    folderContents.stream()
        .map({resource -> ListViewItem(resource) })
        .forEach({ items.add(it) })
    listView.items = items
  }

  /**
   * Property returning the selected resource.
   */
  val selectedResource: Optional<T>
    get() {
      val result = listView.selectionModel.selectedItem?.resource?.value
      return Optional.ofNullable(result)
    }

  fun filter(byValue: String) {
    if (myContents == null) {
      return
    }
    val result = FXCollections.observableArrayList(myContents)
        .filter { it.name.toLowerCase().contains(byValue.toLowerCase()) }
    reloadItems(FXCollections.observableArrayList(result))
  }

  fun requestFocus() {
    this.listView.requestFocus()
    this.listView.selectionModel.selectIndices(0)
  }

  fun isSelectedTopmost(): Boolean {
    return this.listView.selectionModel.isSelected(0)
  }
}

class ListViewItem<T:FolderItem>(resource: T) {
  val isSelected: BooleanProperty = SimpleBooleanProperty()
  val resource: ObjectProperty<T>

  init {
    this.resource = SimpleObjectProperty(resource)
  }
}

fun <T: FolderItem> createExtractor() : Callback<ListViewItem<T>, Array<Observable>> {
  return Callback { item ->
    item?.let {
      arrayOf(
          item.isSelected as Observable, item.resource as Observable)
    } ?: emptyArray()
  }
}

fun <T: FolderItem> createListCell(
    dialogUi: StorageDialogBuilder.DialogUi,
    onDeleteResource: Runnable,
    onToggleLockResource: Runnable,
    isLockingSupported: BooleanProperty) : ListCell<ListViewItem<T>> {
  return object : ListCell<ListViewItem<T>>() {
    override fun updateItem(item: ListViewItem<T>?, empty: Boolean) {
      try {
        doUpdateItem(item, empty)
      } catch (e: WebDavResource.WebDavException) {
        dialogUi.error(e)
      }

    }

    @Throws(WebDavResource.WebDavException::class)
    private fun doUpdateItem(item: ListViewItem<T>?, empty: Boolean) {
      if (item == null) {
        text = ""
        graphic = null
        return
      }
      super.updateItem(item, empty)
      if (empty) {
        text = ""
        graphic = null
        return
      }
      val hbox = HBox()
      hbox.styleClass.add("webdav-list-cell")
      val isLocked = item.resource.value.isLocked
      val isLockable = item.resource.value.isLockable
      if (isLockable && !isLockingSupported.value) {
        isLockingSupported.value = true
      }

      val icon = if (isLocked)
        FontAwesomeIconView(FontAwesomeIcon.LOCK)
      else
        FontAwesomeIconView(FontAwesomeIcon.FOLDER)
      if (!item.resource.value.isDirectory) {
        icon.styleClass.add("hide")
      } else {
        icon.styleClass.add("icon")
      }
      val label = Label(item.resource.value.name, icon)
      hbox.children.add(label)
      if (item.isSelected.value!! && !item.resource.value.isDirectory) {
        val btnBox = HBox()
        btnBox.styleClass.add("webdav-list-cell-button-pane")
        val btnDelete = Button("", FontAwesomeIconView(FontAwesomeIcon.TRASH))
        btnDelete.addEventHandler(ActionEvent.ACTION) { _ -> onDeleteResource.run() }

        var btnLock: Button? = null
        if (isLocked) {
          btnLock = Button("", FontAwesomeIconView(FontAwesomeIcon.UNLOCK))
        } else if (isLockable) {
          btnLock = Button("", FontAwesomeIconView(FontAwesomeIcon.LOCK))
        }
        if (btnLock != null) {
          btnLock.addEventHandler(ActionEvent.ACTION) { _ -> onToggleLockResource.run() }
          btnBox.children.add(btnLock)
        }
        btnBox.children.add(btnDelete)
        HBox.setHgrow(btnBox, Priority.ALWAYS)
        hbox.children.add(btnBox)
      } else {
        val placeholder = Button("")
        placeholder.styleClass.add("hide")
        hbox.children.add(placeholder)
      }
      graphic = hbox
    }
  }
}

data class BreadcrumbNode(val path: Path, val label: String) {
  override fun toString(): String = this.label
}

class BreadcrumbView(initialPath: Path, val onSelectCrumb: Consumer<Path>) {
  val breadcrumbs = BreadCrumbBar<BreadcrumbNode>()
  var path: Path
    get() = breadcrumbs.selectedCrumb.value.path
    set(value) {
      var lastItem: TreeItem<BreadcrumbNode>? = null
      for (idx in 1..value.nameCount) {
        val treeItem = TreeItem<BreadcrumbNode>(
            BreadcrumbNode(value.root.resolve(value.subpath(0, idx)),
                value.getName(idx - 1).toString()))
        if (lastItem != null) {
          lastItem.children.add(treeItem)
        }
        lastItem = treeItem
        breadcrumbs.selectedCrumb = lastItem
      }
      onSelectCrumb.accept(value)
    }

  init {
    breadcrumbs.styleClass.add("breadcrumb")
    breadcrumbs.onCrumbAction = EventHandler { node ->
      node.selectedCrumb.children.clear()
      onSelectCrumb.accept(node.selectedCrumb.value.path)
    }
    path = initialPath
  }

  fun append(name: String) {
    val selectedPath = breadcrumbs.selectedCrumb.value.path
    val appendPath = selectedPath.resolve(name)
    val treeItem = TreeItem<BreadcrumbNode>(BreadcrumbNode(appendPath, name))
    breadcrumbs.selectedCrumb.children.add(treeItem)
    breadcrumbs.selectedCrumb = treeItem
    onSelectCrumb.accept(appendPath)
  }

  fun pop() {
    val parent = breadcrumbs.selectedCrumb.parent ?: return
    parent.children.clear()
    breadcrumbs.selectedCrumb = parent
    onSelectCrumb.accept(parent.value.path)
  }

}