/* Copyright 2013 Laurent Bovet <laurent.bovet@windmaster.ch>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

var dojoApp = require([
  "dojo/aspect", "dojo/ready", "dojo/parser", "dojo/store/JsonRest", "dojo/store/Observable",
  "dijit/Tree", "dijit/tree/ObjectStoreModel", "dojo/store/Memory", "dojo/_base/array", "dijit/layout/ContentPane", "dijit/layout/BorderContainer",
  "dojo/data/ItemFileReadStore", "dijit/Menu", "dijit/MenuItem", "dojo/mouse", "dojo/on", "dojo/dom", "dijit/registry"
], function (aspect, ready, parser, JsonRest, Observable, Tree, ObjectStoreModel, Memory, array, Menu, MenuItem, mouse, on, dom, registry) {

  function getURLParameter(name) {
    return decodeURI(
      (new RegExp(name + '=' + '(.+?)(&|$)').exec(location.search) || [,null])[1]
    );
  }

  ready(function () {

    // Variable definitions
    var root, uri, src, contextItem, contextNode, disabledNode = false;
    var loadedItems = []; // loaded dojo tree items
    var loadedItemNames = []; // loaded dojo tree item names
    var on = require("dojo/on"), dom = require("dojo/dom"), mouse = require("dojo/mouse");
    dojoApp.mouse = mouse;

    var show = function (id) {
      if (id) {
        uri = id;
      }
      if (uri) {
        var editor = getURLParameter(name);
        if (!editor || editor === "null") {
          editor = "editor.html";
        }
        var newSrc = $("#edit-switch").hasClass("active") ? "/playground/server/pages/" + editor + "#" + uri : uri;
        if (src === newSrc) {
          $("#frame").attr("src", "");
          setTimeout(function () {
            $("#frame").attr("src", src);
          }, 100);
        }
        src = newSrc;
        $("#frame").attr("src", src);
      }
    };

    function prepare() {
      var store = new dojo.data.ItemFileReadStore({
        data: {
          identifier: 'id',
          label: 'label',
          items: rawdata

        }
      });
    }

    // function to refresh a tree behind a node
    require(["dojo/_base/lang", "dijit/Tree"], function (lang, Tree) {
      lang.extend(Tree, {
        // call this function with tree.reloadNode
        reloadNode: function (nodeId) {
          nodes = this.getNodesByItem({"id": nodeId});// Create dummyitem&search for nodes
          if (nodes[0] !== undefined) { // Is there a node with this id ?
            nodes[0]._loadDeferred = false; // Reload the first node with this id
            this._expandNode(nodes[0]);// this = "dijit/Tree"
          }
        }
      });
    });

    // Delete node or files
    dojoApp.deleteNode = function deleteNode() {
      if (contextItem !== null) {
        var r = confirm("Are you sure to delete this item '" + contextItem.name + "'?");
        var parentNode = contextNode.getParent();
        if (r === true) {
          // remove item from store
          store.remove(contextItem.id);
          // remove in the tree the items
          contextNode.destroyRecursive();
        }
      } else {
        return "ERROR: you have to select an element!";
      }
    };

    // Add node or files
    dojoApp.addNode = function addNode() {
      if (contextItem !== null && contextItem.folder !== null) {
        var file = prompt("Please enter new file name. If you want to create a folder you have to use this format 'foldername/filename'.", "");
        if (file !== null) {
          // add a new item into the store
          // if you want to have subfolders you have to write 'foldername/filname.extension'
          store.add({id: contextItem.id + file});
          // reload the tree with the function tree.reload
          tree.reloadNode(contextItem.id);
        } else {
          return "ERROR: no answer was returned.";
        }
      } else {
        // debug output when you want to create a file in a file or you don't have selected an node
        return "ERROR: a file can't have include other files or folders or you didn't have selected a node!";
      }
    };

    $("#edit-switch").click(function () {
      $("#edit-switch").toggleClass("active");
      show();
    });

    $("#close").click(function () {
      if ($("#frame").attr("src")) {
        window.location = $("#frame").attr("src");
      }
    });

    var init = function () {
      window.onhashchange = $.noop;

      if (!window.location.hash) {
        window.location.hash = "#/";
      }
      if (!window.location.hash.match(/.*\/$/)) {
        window.location.hash = window.location.hash + "/";
      }
      title = $("#title");
      root = window.location.hash.split("#")[1];
      var path = "";
      title.append("<a href='/'>/</a>");
      $.each(root.split('/'), function (k, part) {
        if (part.trim() !== "") {
          path = path + part + "/";
          title.append("<a href='/" + path + "'>" + part + "/</a>");
        }
      });
      title.toggle();
      title.toggle();
      window.onhashchange = function () {
        window.location.reload();
      };
    };

    init();

    // check if alternativ store is loaded
    if (window.altStore !== undefined) {
      console.info("taking alternative store!");
      var store = testStore();
      // for Memory stores an ObjectStoreModel is required
      var model = new ObjectStoreModel({
        store: store,
        // query to get root node
        query: {id: 'root'}
      });
    }
    else {
      console.info("taking standard store!");
      var store = new JsonRest({
        target: ""
      });
      var model = new ObjectStoreModel({
        store: store,
        mayHaveChildren: function (object) {
          return object.folder;
        },
        getChildren: function (object, onComplete, onError) {
          store.get(object.id).then(function (resp) {
            var children = [];
            var c = resp[Object.keys(resp)[0]];
            array.forEach(c, function (el) {
              children.push({ id : object.id + el, name: el.replace("/", ""), folder: el.match(/\/$/) });
            });
            onComplete(children);
          }, onError).then(function () {}, function (err) {console.error(err); });
        },
        getRoot: function (onItem, onError) {
          onItem({id : root, name: root, folder: true});
        },
        getLabel: function (object) {
          loadedItems[loadedItems.length] = object;
          loadedItemNames[loadedItemNames.length] = object.name;
          return object.name;
        }
      });
    }
    // <-- end if

    var expandRecursive = function (node) {
      return tree._expandNode(node).then(function () {
        var children = node.getChildren();
        if (children && children.length === 1) {
          var child = children[0];
          if (child.hasChildren() && !child.isExpanded) {
            expandRecursive(child);
          }
        }
      });
    };

    var tree = new Tree({
      persist: false,
      model: model,
      showRoot: false,
      onClick: function (item, node) {
        if (item.folder) {
          if (node.isExpanded) {
            node.collapse();
          } else {
            expandRecursive(node);
          }
        } else {
          show(item.id);
        }
      },
      getIconClass: function (item, opened) {
        if (item.folder) {
          if (opened) {
            return "icon-folder-open light";
          } else {
            return "icon-folder-close light";
          }
        } else {
          var parts = item.name.split(".");
          var ext = parts[parts.length - 1];
          var type = mimeTypesByExtension[ext];
          if (type) {
            switch (type.split("/")[0]) {
              case "text":
                return "icon-align-left lighter";
              case "image":
                return "icon-picture lighter";
              case "video":
                return "icon-film lighter";
              case "audio":
                return "icon-music lighter";
              default:
                return "icon-file lighter";
            }
          } else {
            return "icon-file lighter";
          }
        }
      }
    }, "tree");

    // testing features
    tree.startup();

    // include dijit.menu
    var menu = new dijit.Menu();
    menu.bindDomNode(tree.domNode);

    var nodeNew;
    var node;
    // function for catch mouseclicks for the contextmenu
    dojoApp.mouseEvent = function (event) {
      // catch left click
      if (mouse.isLeft(event)) {
      } else if (mouse.isRight(event)) {
        // catch right click
        nodeNew = dijit.getEnclosingWidget(event.target);
        node = tree.selectedNode;

        if (node !== undefined && node !== null) {
          // when a other node is selected - disselect it
          node.setSelected(false);
        }
        // select the right clicked item
        nodeNew.setSelected(true);
        contextNode = nodeNew;
        contextItem = nodeNew.item;
        // check if the right clicked item is a file or folder
        if (contextItem.folder === null) {
          // files can't have chields, so don't allow this and disable the menu item
          require(["dijit/registry"], function (registry) {
            registry.byId("menuAdd").set("disabled", true);
          });
        } else if (contextItem !== null) {
          // folders can have chields so enable the menu item
          require(["dijit/registry"], function (registry) {
            registry.byId("menuAdd").set("disabled", false);
          });
        } else {
          console.error("fail add context item");
        }
      }
    };

    // catch mouseevents on the tree
    on(tree.domNode, on.selector("#tree", 'mousedown'), function (event) {
      dojoApp.mouseEvent(event);
    });

    // create menu item
    menu.addChild(new dijit.MenuItem({
      label: "Delete folder / file",
      onClick: function (evt) {
        var node = this.getParent().currentTarget;
        dojoApp.deleteNode();
      }
    }));
    // create menu item
    var men = menu.addChild(new dijit.MenuItem({
      label: "Add folder / file",
      id: "menuAdd",
      disabled: disabledNode,
      onClick: function (evt) {
        var node = this.getParent().currentTarget;
        dojoApp.addNode();
      }
    }));
    // build menu
    menu.startup();
  });
});