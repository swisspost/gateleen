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

function reformat() {
    try {
        var pos = editor.getCursorPosition();
        var json = JSON.parse(editor.getSession());
        editor.getSession().setValue(JSON.stringify(json, null, 2));
        editor.navigateTo(pos.row, pos.column);
    } catch(err) {
        // ignore
    }
}

function logReq(text) {
    log('<pre class="info">'+text+'</pre>');
}

function logOk(text) {
    log('<pre class="ok">'+text+'</pre><br>');
}

function logError(text) {
    log('<pre class="error">'+$('<div/>').text(text).html()+'</pre><br>');
}

function validateLog(status, message) {
    if(status === "error") {
        log('<span class="warn">'+message+'</span><br><br>')
    } else if(status === "warn") {
        log('<span class="info">'+message+'</span><br><br>')
    }
}

function log(html) {
    $("#log").append(html);
    $("#log").scrollTop($("#log")[0].scrollHeight);
}

function get() {
    logReq("GET "+uri);
    var p = $.ajax(uri, { dataType:"text", headers: getHeaders() });
    p.done( function(data, status, xhr)
    {
        $("#title").css({ "font-style": "normal" });
        logOk(xhr.status+" "+xhr.statusText);
        var type=xhr.getResponseHeader("Content-Type");
        if(type && type.indexOf("application/json") != -1) {
            editor.getSession().setMode("ace/mode/json");
            editor.getSession().setValue(data);
            reformat();
            validate(uri, "GET/out", JSON.parse(data), validateLog);
        } else {
            var mimeType = mimeTypes[type.split(';')[0]];
            var path;
            if(mimeType && type.split(';')[0] != "text/plain") {
                if(mimeType[2]) {
                    if(binary) {
                        document.getElementById("frame").contentDocument.location.reload(true);
                    } else {
                        setBinary(true, true);
                    }
                    return;
                } else {
                    if(mimeType[1]) {
                        path=".xml";
                    } else {
                        path="."+mimeType[0];
                    }
                }
            } else {
                path=uri.split('?')[0];
            }
            var mode = getModeFromPath(path);
            if(mode) {
                editor.getSession().setMode(mode.mode);
            } else {
                editor.getSession().setMode("ace/mode/text");
            }
            $("#reformat").hide();
            editor.getSession().setValue(data);
        }
    });
    p.fail( function(xhr, status, err)
    {
        $("#title").css({ "font-style": "italic" });
        logError(xhr.status+" "+xhr.statusText+"\n");
        initEmpty();
    });
}

function put() {
    var d = $.Deferred();
    if(postMode) {
        logError("Use Ctrl-Enter to post");
        return;
    }
    logReq("PUT "+uri);
    try {
        validate(uri, "PUT/in", JSON.parse(editor.getSession().getValue()), validateLog)
    } catch(e) {
        // ignore
    }
    var p = $.ajax(uri, {
        method: "PUT",
        data: binary ? blob: editor.getSession().getValue(),
        contentType: defaultType,
        processData: false,
        headers: getHeaders()
    });
    p.done( function(data, status, xhr)
    {
        $("#title").css({ "font-style": "normal" });
        logOk(xhr.status+" "+xhr.statusText);
        d.resolve();
    });
    p.fail( function(xhr, status, err)
    {
        logError(xhr.status+" "+xhr.statusText+"\n"+ ((status==400 || status >= 500) ? xhr.responseText : ""));
        d.reject();
    });
    return d.promise();
}

function post() {
    logReq("POST "+uri);
    try {
        validate(uri, "POST/in", JSON.parse(editor.getSession().getValue()), validateLog)
    } catch(e) {
        // ignore
    }
    var p = $.ajax(uri, {
        method: "POST",
        data: binary ? blob: editor.getSession().getValue(),
        contentType: defaultType,
        processData: false,
        headers: getHeaders()
    });
    p.done( function(data, status, xhr)
    {
        logOk(xhr.status+" "+xhr.statusText);
        try {
            logOk(JSON.stringify(data, null, 2));
        } catch(e) {
            // ignore
        }
        try {
            validate(uri, "POST/out", JSON.parse(data), validateLog)
        } catch(e) {
            // ignore
        }
    });
    p.fail( function(xhr, status, err)
    {
        logError(xhr.status+" "+xhr.statusText+"\n"+ ((status==400 || status >= 500) ? xhr.responseText : ""));
    });
}

function del() {
    $.confirm("Are you sure you want to delete this resource?", "Confirm Delete",
        function() {
            logReq("DELETE "+uri);
            var p = $.ajax(uri, {
                method: "DELETE",
                headers: getHeaders()
            });
            p.done( function(data, status, xhr)
            {
                $("#title").css({ "font-style": "italic" });
                logOk(xhr.status+" "+xhr.statusText);
            });
            p.fail( function(xhr, status, err)
            {
                logError(xhr.status+" "+xhr.statusText+"\n");
            });
        });
}

function openHeader() {
    $("#header-button").hide();
    $("#new-header-container").show();
    $("#new-header").focus();
}

function closeHeader() {
    $("#header-button").show();
    $("#new-header-container").hide();
    return false;
}

function addHeader(header, star) {
    $("#new-header-form").submit();
    $("#headers").append('<div class="header"><i class="icon-star'+ (!star ? '-empty' : '') +' header-star"></i><span title="Edit Header" class="header-value">'+header+'</span><i class="icon-remove del-header"></i></div>');
    $(".del-header").unbind("click");
    $(".del-header").click( function() {
        $(this).parent().remove();
        updateHeaderBar();
    });
    $(".header-star").unbind("click");
    $(".header-star").click( function() {
        toggleStar($(this).parent());
    });
    $(".header-value").click( function() {
        $("#new-header").val($(this).text());
        removeStar($(this).text());
        $(this).parent().remove();
        openHeader();
        updateHeaderBar();
    });
    $("#editor").css("top", "80px");
}

function toggleStar(headerElement) {
    var header = headerElement.children(".header-value").text();
    var star = headerElement.children(".header-star");
    star.toggleClass("icon-star");
    star.toggleClass("icon-star-empty");
    var headers = $.cookie("headers") || [];
    if(star.hasClass("icon-star")) {
        removeStar(header);
    } else {
        var pos = $.inArray(header, headers);
        if(pos != -1) {
            headers.splice(pos, 1);
            $.cookie("headers", headers);
        }
    }
}

function removeStar(header) {
    var headers = $.cookie("headers") || [];
    if($.inArray(header, headers)==-1) {
        headers.push(header);
        $.cookie("headers", headers);
    }
}

function updateHeaderBar() {
    if($("#headers").children().length === 0) {
        $("#editor").css("top", "48px");
    }
}

function getHeaders() {
    var result = {};
    $("#headers").find(".header-value").each(function() {
        var splitted = $(this).text().split(":");
        result[splitted[0]]=splitted[1];
    });
    return result;
}

function switchMode() {
    window.onhashchange = function() {};
    if(!postMode) {
        window.location.assign(window.location.href.replace(/\??.[^#]*#/, "?mode=post#"));
    } else {
        window.location.assign(window.location.href.replace(/mode=post/, ""));
    }
}

function setBinary(value, load) {
    var editorElement;

    if(value && !binary) {
        var frame = $('<iframe id="frame" />');
        frame.css("height", "100%");
        frame.css("overflow", "scroll");
        frame.height($("#editor").height());
        frame.width($("#editor").width());
        frame.css("border", "0");
        if(load) {
            frame.attr('src', uri);
        }
        $("#editor").html(frame);
        $("#reformat").hide();
        $("#save").remove();
        $("#mode-switch").hide();
    }
    binary = value;
}

function initEmpty() {
    var mimeType = mimeTypes[defaultType];
    var path;
    if(mimeType) {
        if(mimeType[2]) {
            if(binary) {
                document.getElementById("frame").contentDocument.location.reload(true);
            } else {
                setBinary(true);
            }
            return;
        } else {
            if(mimeType[1]) {
                path=".xml";
            } else {
                path="."+mimeType[0];
            }
        }
    } else {
        path=uri.split('?')[0];
    }
    var mode = getModeFromPath(path);
    if(mode && mode.mode!="ace/mode/json") {
        editor.getSession().setMode(mode.mode);
    } else {
        editor.getSession().setMode("ace/mode/json");
        editor.getSession().setValue("{\n\t\n}");
        editor.navigateTo(1, 1);
    }
    $("#reformat").hide();
    $("#title").css({ "font-style": "italic" });
}

function init() {
    $.cookie.json = true;
    uri = window.location.hash;
    if(uri) {
        uri = uri.substring(1,uri.length);
        var tokens = uri.split("?")[0].split(".");
        var extension = tokens[tokens.length-1];
        defaultType = mimeTypesByExtension[extension];
        if(!defaultType) {
            defaultType = "application/json";
        }
        var lastSlash;
        if(uri.lastIndexOf("/") == uri.length-1) {
            lastSlash = uri.substring(0,uri.length-1).lastIndexOf("/");
        } else {
            lastSlash = uri.lastIndexOf("/");
        }
        if(lastSlash == -1) {
            lastSlash=0;
        }
        var docName = uri.substring(lastSlash+1);
        document.title = docName;
        var create = window.location.search.indexOf("new=true") != -1;
        postMode = window.location.search.indexOf("mode=post") != -1;

        var logSize = $.cookie("log-size");
        if(logSize) {
            if(logSize > $(window).height() -100) {
                logSize = $(window).height() -100;
            }
            $("#log-container").css("height", logSize);
            $("#splitter").css("top", 0);
            $("#log").css("top", $("#splitter").height());
            $("#editor").css("bottom", logSize);
        }

        $("#frame").remove();
        editor = ace.edit("editor");
        editor.setTheme("ace/theme/textmate");
        editor.getSession().setTabSize(2);
        editor.getSession().setUseSoftTabs(true);

        if(logSize) {
            editor.resize();
        }

        $("#title").attr("href", uri);
        $("#title").html(docName.split("?")[0]);
        $("#splitter").draggable({
            axis: "y",
            containment: "window",
            drag: function( event, ui ) {
                logSize = $("#log-container").height()-ui.position.top;
                if(logSize>=$(window).height()) {
                    return false;
                }
                $("#log").css("top", ui.position.top);
                $("#editor").css("bottom", logSize);
                if(binary) {
                    $("#frame").css("height", $("#editor").css("height"));
                } else {
                    editor.resize();
                }
            },
            stop: function() {
                var size=$("#editor").position().top;
                $("log-container").height(logSize);
                $.cookie("log-size", logSize);
            }
        });
        if(!create && !postMode) {
            editor.getSession().setValue("");
            get();
        }
        if(postMode) {
            $(".mode-normal").hide();
            $(".mode-post").show();
            $("#mode-switch").addClass("active");
            editor.commands.addCommand({
                name: "post",
                bindKey: {win: "Ctrl-Enter", mac: "Command-Enter"},
                exec: post
            });
            initEmpty();
        } else {
            editor.commands.addCommand({
                name: "refresh",
                bindKey: {win: "Ctrl-R", mac: "Command-R"},
                exec: get
            });
        }
        editor.commands.addCommand({
            name: "save",
            bindKey: {win: "Ctrl-S", mac: "Command-S"},
            exec: put
        });
        editor.commands.addCommand({
            name: "reformat",
            bindKey: {win: "Ctrl-Shift-F", mac: "Command-Shift-F"},
            exec: reformat
        });
        if(create) {
            var p = $.ajax(uri, { dataType:"text" });
            p.done( function(data, status, xhr)
            {
                logError("Resource already exists");
                editor.getSession().setValue(data);
                reformat();
            });
            p.fail( function(xhr, status, err)
            {
                initEmpty();
            });
        }
        editor.focus();
        $("#headers").text("");
        $.each($.cookie("headers") || [], function(pos, header) {
            addHeader(header, true);
        });
        updateHeaderBar();

        $.event.props.push('dataTransfer');
        var body = $('body')
            .bind( 'dragenter dragover', false)
            .bind( 'drop', function( e ) {
                e.stopPropagation();
                e.preventDefault();
                $.each( e.dataTransfer.files, function(index, file){
                    var fileReader = new FileReader();
                    if(!binary) {
                        fileReader.onload = function(e) {
                            editor.getSession().setValue(fileReader.result);
                        };
                        fileReader.readAsText(file);
                    } else {
                        fileReader.onload = function(e) {
                            blob = fileReader.result;
                            put().done(function() {
                                $("#frame").get(0).contentDocument.location.reload(true);
                            });
                        };
                        fileReader.readAsArrayBuffer(file);
                    }
                });
            });
    } else {
        logError("Please specify the resource URI after a # in the address bar. Example: "+window.location+"#/mypath/myresource");
    }
}

$.extend({
    confirm: function(message, title, okAction) {
        $("<div></div>").dialog({
            open: function(event, ui) { $(".ui-dialog-titlebar-close").hide(); },
            buttons: {
                "Ok": function() {
                    $(this).dialog("close");
                    okAction();
                },
                "Cancel": function() {
                    $(this).dialog("close");
                }
            },
            close: function(event, ui) { $(this).remove(); },
            resizable: false,
            title: title,
            modal: true
        }).text(message);
    }
});

$().ready(function() {
    $("#reformat").click(reformat);
    $("#reload").click(get);
    $("#save").click(put);
    $("#post").click(post);
    $("#delete").click(del);
    $("#mode-switch").click(switchMode);
    $("#header-button").click(openHeader);
    $("#header-button-active").click(closeHeader);
    $("#new-header").keypress(function(ev) {
        var keycode = (ev.keyCode ? ev.keyCode : ev.which);
        if((keycode == 13) && $("#new-header").val()) {
            ev.preventDefault();
            addHeader($("#new-header").val());
            $("#new-header").val("");
        }
    });
    init();
});
window.onhashchange = function() {
    if(binary) {
        document.location.reload();
    } else {
        init();
    }
};

var editor;
var postMode;
var binary;
var blob;
var uri;
var defaultType;

var modes = [];
function getModeFromPath(path) {
    var mode;
    var fileName = path.split(/[\/\\]/).pop();
    for (var i = 0; i < modes.length; i++) {
        if (modes[i].supportsFile(fileName)) {
            mode = modes[i];
            break;
        }
    }
    return mode;
}

var Mode = function(name, desc, extensions) {
    this.name = name;
    this.desc = desc;
    this.mode = "ace/mode/" + name;
    var re;
    if (/\^/.test(extensions)) {
        re = extensions.replace(/\|(\^)?/g, function(a, b){
            return "$|" + (b ? "^" : "^.*\\.");
        }) + "$";
    } else {
        re = "^.*\\.(" + extensions + ")$";
    }

    this.extRe = new RegExp(re, "gi");
};

Mode.prototype.supportsFile = function(filename) {
    return filename.match(this.extRe);
};

var modesByName = {
    abap:       ["ABAP"         , "abap"],
    asciidoc:   ["AsciiDoc"     , "asciidoc"],
    c9search:   ["C9Search"     , "c9search_results"],
    coffee:     ["CoffeeScript" , "^Cakefile|coffee|cf|cson"],
    coldfusion: ["ColdFusion"   , "cfm"],
    csharp:     ["C#"           , "cs"],
    css:        ["CSS"          , "css"],
    curly:      ["Curly"        , "curly"],
    dart:       ["Dart"         , "dart"],
    diff:       ["Diff"         , "diff|patch"],
    dot:        ["Dot"          , "dot"],
    ftl:        ["FreeMarker"   , "ftl"],
    glsl:       ["Glsl"         , "glsl|frag|vert"],
    golang:     ["Go"           , "go"],
    groovy:     ["Groovy"       , "groovy"],
    haxe:       ["haXe"         , "hx"],
    haml:       ["HAML"         , "haml"],
    html:       ["HTML"         , "htm|html|xhtml"],
    c_cpp:      ["C/C++"        , "c|cc|cpp|cxx|h|hh|hpp"],
    clojure:    ["Clojure"      , "clj"],
    jade:       ["Jade"         , "jade"],
    java:       ["Java"         , "java"],
    jsp:        ["JSP"          , "jsp"],
    javascript: ["JavaScript"   , "js"],
    json:       ["JSON"         , "json"],
    jsx:        ["JSX"          , "jsx"],
    latex:      ["LaTeX"        , "latex|tex|ltx|bib"],
    less:       ["LESS"         , "less"],
    lisp:       ["Lisp"         , "lisp"],
    scheme:     ["Scheme"       , "scm|rkt"],
    liquid:     ["Liquid"       , "liquid"],
    livescript: ["LiveScript"   , "ls"],
    logiql:     ["LogiQL"       , "logic|lql"],
    lua:        ["Lua"          , "lua"],
    luapage:    ["LuaPage"      , "lp"],
    lucene:     ["Lucene"       , "lucene"],
    lsl:        ["LSL"          , "lsl"],
    makefile:   ["Makefile"     , "^GNUmakefile|^makefile|^Makefile|^OCamlMakefile|make"],
    markdown:   ["Markdown"     , "md|markdown"],
    objectivec: ["Objective-C"  , "m"],
    ocaml:      ["OCaml"        , "ml|mli"],
    pascal:     ["Pascal"       , "pas|p"],
    perl:       ["Perl"         , "pl|pm"],
    pgsql:      ["pgSQL"        , "pgsql"],
    php:        ["PHP"          , "php|phtml"],
    powershell: ["Powershell"   , "ps1"],
    python:     ["Python"       , "py"],
    r:          ["R"            , "r"],
    rdoc:       ["RDoc"         , "Rd"],
    rhtml:      ["RHTML"        , "Rhtml"],
    ruby:       ["Ruby"         , "ru|gemspec|rake|rb"],
    scad:       ["OpenSCAD"     , "scad"],
    scala:      ["Scala"        , "scala"],
    scss:       ["SCSS"         , "scss"],
    sass:       ["SASS"         , "sass"],
    sh:         ["SH"           , "sh|bash|bat"],
    sql:        ["SQL"          , "sql"],
    stylus:     ["Stylus"       , "styl|stylus"],
    svg:        ["SVG"          , "svg"],
    tcl:        ["Tcl"          , "tcl"],
    tex:        ["Tex"          , "tex"],
    text:       ["Text"         , "txt"],
    textile:    ["Textile"      , "textile"],
    tm_snippet: ["tmSnippet"    , "tmSnippet"],
    toml:       ["toml"         , "toml"],
    typescript: ["Typescript"   , "typescript|ts|str"],
    vbscript:   ["VBScript"     , "vbs"],
    xml:        ["XML"          , "xml|rdf|rss|wsdl|xslt|atom|mathml|mml|xul|xbl"],
    xquery:     ["XQuery"       , "xq"],
    yaml:       ["YAML"         , "yaml"]
};

for (var n in modesByName) {
    var mode = modesByName[n];
    mode = new Mode(n, mode[0], mode[1]);
    modesByName[n] = mode;
    modes.push(mode);
}
