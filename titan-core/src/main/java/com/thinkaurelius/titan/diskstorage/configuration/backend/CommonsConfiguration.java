package com.thinkaurelius.titan.diskstorage.configuration.backend;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.thinkaurelius.titan.core.time.Duration;
import com.thinkaurelius.titan.core.time.SimpleDuration;
import com.thinkaurelius.titan.diskstorage.configuration.ReadConfiguration;
import com.thinkaurelius.titan.diskstorage.configuration.WriteConfiguration;

import org.apache.commons.configuration.BaseConfiguration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link ReadConfiguration} wrapper for Apache Configuration
 *
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class CommonsConfiguration implements WriteConfiguration {

    private final Configuration config;

    private static final Logger log =
            LoggerFactory.getLogger(CommonsConfiguration.class);

    public CommonsConfiguration() {
        this(new BaseConfiguration());
    }

    public CommonsConfiguration(Configuration config) {
        Preconditions.checkArgument(config!=null);
        this.config = config;
    }

    public Configuration getCommonConfiguration() {
        return config;
    }

    @Override
    public<O> O get(String key, Class<O> datatype) {
        if (!config.containsKey(key)) return null;

        if (datatype.isArray()) {
            Preconditions.checkArgument(datatype.getComponentType()==String.class,"Only string arrays are supported: %s",datatype);
            return (O)config.getStringArray(key);
        } else if (Number.class.isAssignableFrom(datatype)) {
            // A properties file configuration returns Strings even for numeric
            // values small enough to fit inside Integer (e.g. 5000). In-memory
            // configuration impls seem to be able to store and return actual
            // numeric types rather than String
            //
            // We try to handle either case here
            Object o = config.getProperty(key);
            if (datatype.isInstance(o)) {
                return (O)o;
            } else {
                return constructFromStringArgument(datatype, o.toString());
            }
        } else if (datatype==String.class) {
            return (O)config.getString(key);
        } else if (datatype==Boolean.class) {
            return (O)new Boolean(config.getBoolean(key));
        } else if (datatype.isEnum()) {
            O[] constants = datatype.getEnumConstants();
            Preconditions.checkState(null != constants && 0 < constants.length, "Zero-length or undefined enum");
            String estr = config.getProperty(key).toString();
            for (O c : constants)
                if (c.toString().equals(estr))
                    return c;
            throw new IllegalArgumentException("No match for string \"" + estr + "\" in enum " + datatype);
        } else if (datatype==Object.class) {
            return (O)config.getProperty(key);
        } else if (Duration.class.isAssignableFrom(datatype)) {
            // This is a conceptual leak; the config layer should ideally only handle standard library types
            Object o = config.getProperty(key);
            if (Duration.class.isInstance(o)) {
                return (O)o;
            } else {
                return (O)new SimpleDuration(Long.valueOf(o.toString()), TimeUnit.MILLISECONDS);
            }
        } else throw new IllegalArgumentException("Unsupported data type: " + datatype);
    }

    private <O> O constructFromStringArgument(Class<O> datatype, String arg) {
        try {
            Constructor<O> ctor = datatype.getConstructor(String.class);
            return ctor.newInstance(arg);
        // ReflectiveOperationException is narrower and more appropriate than Exception, but only @since 1.7
        //} catch (ReflectiveOperationException e) {
        } catch (Exception e) {
            log.error("Failed to parse configuration string \"{}\" into type {} due to the following reflection exception", arg, datatype, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Iterable<String> getKeys(String prefix) {
        List<String> result = Lists.newArrayList();
        Iterator<String> keys;
        if (StringUtils.isNotBlank(prefix)) keys = config.getKeys(prefix);
        else keys = config.getKeys();
        while (keys.hasNext()) result.add(keys.next());
        return result;
    }

    @Override
    public void close() {
        //Do nothing
    }

    @Override
    public <O> void set(String key, O value) {
        if (value==null) config.clearProperty(key);
        else config.setProperty(key,value);
    }

    @Override
    public void remove(String key) {
        config.clearProperty(key);
    }

    @Override
    public WriteConfiguration copy() {
        BaseConfiguration copy = new BaseConfiguration();
        copy.copy(config);
        return new CommonsConfiguration(copy);
    }

}
