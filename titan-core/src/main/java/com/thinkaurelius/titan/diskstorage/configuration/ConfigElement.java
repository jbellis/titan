package com.thinkaurelius.titan.diskstorage.configuration;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.HashCodeBuilder;

import java.util.List;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public abstract class ConfigElement {

    public static final char SEPARATOR = '.';

    public static final char[] ILLEGAL_CHARS = new char[]{SEPARATOR,' ','\t','#','@','<','>','?','/',';','"','\'',':','+','(',')','*','^','`','~','$','%','|','\\','{','[',']','}'};

    private final ConfigNamespace namespace;
    private final String name;
    private final String description;

    public ConfigElement(ConfigNamespace namespace, String name, String description) {
        Preconditions.checkArgument(StringUtils.isNotBlank(name),"Name cannot be empty: %s",name);
        Preconditions.checkArgument(!StringUtils.containsAny(name, ILLEGAL_CHARS),"Name contains illegal character: %s (%s)",name,ILLEGAL_CHARS);
        Preconditions.checkArgument(namespace!=null || this instanceof ConfigNamespace,"Need to specify namespace for ConfigOption");
        Preconditions.checkArgument(StringUtils.isNotBlank(description));
        this.namespace = namespace;
        this.name = name;
        this.description = description;
        if (namespace!=null) namespace.registerChild(this);
    }

    public ConfigNamespace getNamespace() {
        Preconditions.checkArgument(namespace !=null,"Cannot get namespace of root");
        return namespace;
    }

    public boolean isRoot() {
        return namespace ==null;
    }

    public ConfigNamespace getRoot() {
        if (isRoot()) return (ConfigNamespace)this;
        else return getNamespace().getRoot();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public abstract boolean isOption();

    public boolean isNamespace() {
        return !isOption();
    }

    @Override
    public String toString() {
        return (namespace !=null? namespace.toString()+SEPARATOR:"") + name;
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(name).append(namespace).toHashCode();
    }

    @Override
    public boolean equals(Object oth) {
        if (this==oth) return true;
        else if (oth==null || !getClass().isInstance(oth)) return false;
        ConfigElement c = (ConfigElement)oth;
        return name.equals(c.name) && namespace ==c.namespace;
    }

    public static String[] getComponents(final String path) {
        return StringUtils.split(path,SEPARATOR);
    }

    public static String toStringSingle(ConfigElement element) {
        return toStringSingle(element,"");
    }

    private static String toStringSingle(ConfigElement element, String indent) {
        String result = element.getName();
        if (element.isNamespace()) {
            result = "+ " + result;
            if (((ConfigNamespace)element).isUmbrella())
                result += " [*]";
        } else {
            result = "- " + result;
            ConfigOption option = (ConfigOption)element;
            result+= " [";
            switch (option.getType()) {
                case FIXED: result+="f"; break;
                case GLOBAL_OFFLINE: result+="g!"; break;
                case GLOBAL: result+="g"; break;
                case MASKABLE: result+="m"; break;
                case LOCAL: result+="l"; break;
            }
            result+=","+option.getDatatype().getSimpleName();
            result+=","+option.getDefaultValue();
            result+="]";
        }
        result = indent + result + "\n" + indent;
        String desc = element.getDescription();
        result+="\t"+'"'+desc.substring(0, Math.min(desc.length(), 50))+'"';
        return result;
    }

    public static String toString(ConfigElement element) {
        return toStringRecursive(element,"");
    }

    private static String toStringRecursive(ConfigElement element, String indent) {
        String result = toStringSingle(element, indent) + "\n";
        if (element.isNamespace()) {
            ConfigNamespace ns = (ConfigNamespace)element;
            indent += "\t";
            for (ConfigElement child : ns.getChildren()) {
                result += toStringRecursive(child,indent);
            }
        }
        return result;
    }

    public static String getPath(ConfigElement element, String... umbrellaElements) {
        Preconditions.checkNotNull(element);
        if (umbrellaElements==null) umbrellaElements = new String[0];
        String path = element.getName();
        int umbrellaPos = umbrellaElements.length-1;
        while (!element.isRoot() && !element.getNamespace().isRoot()) {
            ConfigNamespace parent = element.getNamespace();
            if (parent.isUmbrella()) {
                Preconditions.checkArgument(umbrellaPos>=0,"Missing umbrella element path");
                String umbrellaName = umbrellaElements[umbrellaPos];
                Preconditions.checkArgument(!StringUtils.containsAny(umbrellaName,ILLEGAL_CHARS),"Invalid umbrella name provided: %s. Contains illegal chars",umbrellaName);
                path = umbrellaName + SEPARATOR + path;
                umbrellaPos--;
            }
            path = parent.getName() + SEPARATOR + path;
            element = parent;
        }
        //Don't make this check so that we can still access more general config items
        Preconditions.checkArgument(umbrellaPos<0,"Found unused umbrella element: %s",umbrellaPos<0?null:umbrellaElements[umbrellaPos]);
        return path;
    }

    public static PathIdentifier parse(ConfigNamespace root, String path) {
        Preconditions.checkNotNull(root);
        if (StringUtils.isBlank(path)) return new PathIdentifier(root,new String[]{},false);
        String[] components = getComponents(path);
        Preconditions.checkArgument(components.length>0,"Empty path provided: %s",path);
        List<String> umbrellaElements = Lists.newArrayList();
        ConfigNamespace parent = root;
        ConfigElement last = root;
        boolean lastIsUmbrella = false;
        for (int i=0;i<components.length;i++) {
            if (parent.isUmbrella() && !lastIsUmbrella) {
                umbrellaElements.add(components[i]);
                lastIsUmbrella = true;
            } else {
                last = parent.getChild(components[i]);
                Preconditions.checkArgument(last!=null,"Unknown configuration element in namespace [%s]: %s",parent.toString(),components[i]);
                if (i+1<components.length) {
                    Preconditions.checkArgument(last instanceof ConfigNamespace,"Expected namespace at position [%s] of [%s] but got: %s",i,path,last);
                    parent = (ConfigNamespace)last;
                }
                lastIsUmbrella = false;
            }
        }
        return new PathIdentifier(last,umbrellaElements.toArray(new String[umbrellaElements.size()]), lastIsUmbrella);
    }

    public static class PathIdentifier {

        public final ConfigElement element;
        public final String[] umbrellaElements;
        public final boolean lastIsUmbrella;

        private PathIdentifier(ConfigElement element, String[] umbrellaElements, boolean lastIsUmbrella) {
            this.lastIsUmbrella = lastIsUmbrella;
            Preconditions.checkNotNull(element);
            Preconditions.checkNotNull(umbrellaElements);
            this.element = element;
            this.umbrellaElements = umbrellaElements;
        }

        public boolean hasUmbrellaElements() {
            return umbrellaElements.length>0;
        }

    }


}
