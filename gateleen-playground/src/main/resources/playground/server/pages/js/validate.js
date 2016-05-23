function validate(uri, type, json, cb) {
    if(json.$schema==="http://json-schema.org/draft-04/schema#") {
        cb("ok", "I don't validate schemas");
        return;
    }
    console.log(json)
    function doValidate(base, segments) {
        $.get(base).done(function(data) {
            segments = segments[0] === "" ? segments.splice(1) : segments;
            if(segments.length === 0) {
                if(data.$schema && data.$schema==="http://json-schema.org/draft-04/schema#") {
                    var result = tv4.validateMultiple(json, data)
                    if(result.valid) {
                        cb("ok", "Great, this JSON is valid against schema <a target='_blank' href='/nemo/server/pages/editor.html#"+base+"'>"+base+"</a>");
                    } else {
                        cb("error", "Oops, this JSON is not valid");
                        $.each(result.errors, function(k,error) {
                            cb("error", "<b>"+error.message+"</b> at "+error.dataPath+"<br>Schema path: "+error.schemaPath);
                        });
                        cb("error", "Using schema: <a target='_blank' href='/nemo/server/pages/editor.html#"+base+"'>"+base+"</a>");
                    }
                } else {
                    cb("warn", "No schema for "+uri+" ("+type+")");
                }
            } else {
                var newBase = base;
                var list = data[base.replace(/\/$/, "").split("/").splice(-1)[0]]+"/";
                if(list) {
                    if(list.indexOf(segments[0]+"/") > -1) {
                        newBase += segments[0]+"/";
                        doValidate(newBase, segments.splice(1));
                    } else if(list.indexOf("_/") > -1) {
                        newBase += "_/";
                        doValidate(newBase, segments.splice(1));
                    } else {
                        cb("warn", "No schema for "+uri+" ("+type+")");
                    }
                } else {
                    cb("warn", "No schema for "+uri+" ("+type+")");
                }
            }
        }).fail(function(xhr, status) {
                cb("warn", "Could not get path "+base);
        });
    }

    doValidate("/nemo/schemas/apis/", (uri.replace(/^\//, "")+"/"+type).split("/"));
}