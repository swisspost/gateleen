package org.swisspush.gateleen.expansion.expansion;

/**
 * A helper model class for representing a resource.
 * Describes the resource by name and the content as a
 * JsonObject.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class ResourceNode {
    private String nodeName;
    private Object object;
    private String eTag;
    private String path;

    /**
     * Creates an instance of a ResourceNode.
     * 
     * @param nodeName - name of the resource
     * @param object - JsonObject, JsonArray or an Exception
     * @param eTag - eTag of the request leading to this resource
     * @param path - the request path
     */
    public ResourceNode(String nodeName, Object object, String eTag, String path) {
        this.nodeName = nodeName;
        this.object = object;
        this.eTag = eTag;
        this.path = path;
    }

    /**
     * Creates an instance of a ResourceNode.
     * 
     * @param nodeName - name of the resource
     * @param object - JsonObject, JsonArray or an Exception
     * @param eTag - eTag of the request leading to this resource
     */
    public ResourceNode(String nodeName, Object object, String eTag) {
        this(nodeName, object, eTag, "");
    }

    /**
     * Creates an instance of a ResourceNode.
     * 
     * @param nodeName - name of the resource
     * @param object - JsonObject, JsonArray or an Exception
     */
    public ResourceNode(String nodeName, Object object) {
        this(nodeName, object, null, "");
    }

    /**
     * Returns the eTag for this resource or
     * a collection of all eTags from this resource
     * and their children.
     * 
     * @return String
     */
    public String geteTag() {
        return eTag;
    }

    /**
     * Returns the name of this resource.
     * 
     * @return String
     */
    public String getNodeName() {
        return nodeName;
    }

    /**
     * Returns a JsonObject, a JsonArray,
     * a list with ResourceNodes, a byte
     * array or an Exception.
     * The object can be null!
     * 
     * @return Object
     */
    public Object getObject() {
        return object;
    }

    /**
     * Returns the path for
     * the request.
     * 
     * @return String
     */
    public String getPath() {
        return path;
    }

    /**
     * Sets a JsonObject, a JsonArray,
     * a list with ResourceNodes, a byte
     * array or an Exception.
     * The object can be null!
     * 
     * @param object object
     */
    public void setObject(Object object) {
        this.object = object;
    }
}
