package com.thinkaurelius.titan.hadoop.mapreduce.transform;

import com.thinkaurelius.titan.hadoop.HadoopEdge;
import com.thinkaurelius.titan.hadoop.HadoopVertex;
import com.thinkaurelius.titan.hadoop.Tokens;
import com.thinkaurelius.titan.hadoop.mapreduce.util.ElementPicker;
import com.thinkaurelius.titan.hadoop.mapreduce.util.EmptyConfiguration;
import com.thinkaurelius.titan.hadoop.mapreduce.util.SafeMapperOutputs;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Vertex;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import java.io.IOException;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class PropertyMapMap {

    public static final String CLASS = Tokens.makeNamespace(PropertyMapMap.class) + ".class";

    public enum Counters {
        VERTICES_PROCESSED,
        OUT_EDGES_PROCESSED
    }

    public static Configuration createConfiguration(final Class<? extends Element> klass) {
        final Configuration configuration = new EmptyConfiguration();
        configuration.setClass(CLASS, klass, Element.class);
        return configuration;
    }

    public static class Map extends Mapper<NullWritable, HadoopVertex, LongWritable, Text> {

        private boolean isVertex;
        private SafeMapperOutputs outputs;

        @Override
        public void setup(final Mapper.Context context) throws IOException, InterruptedException {
            this.isVertex = context.getConfiguration().getClass(CLASS, Element.class, Element.class).equals(Vertex.class);
            this.outputs = new SafeMapperOutputs(context);
        }

        private LongWritable longWritable = new LongWritable();
        private Text text = new Text();

        @Override
        public void map(final NullWritable key, final HadoopVertex value, final Mapper<NullWritable, HadoopVertex, LongWritable, Text>.Context context) throws IOException, InterruptedException {
            if (this.isVertex) {
                if (value.hasPaths()) {
                    this.longWritable.set(value.getIdAsLong());
                    this.text.set(ElementPicker.getPropertyAsString(value, Tokens._PROPERTIES));
                    for (int i = 0; i < value.pathCount(); i++) {
                        this.outputs.write(Tokens.SIDEEFFECT, this.longWritable, this.text);
                    }
                    context.getCounter(Counters.VERTICES_PROCESSED).increment(1l);
                }
            } else {
                long edgesProcessed = 0;
                for (final Edge e : value.getEdges(Direction.OUT)) {
                    final HadoopEdge edge = (HadoopEdge) e;
                    if (edge.hasPaths()) {
                        this.longWritable.set(edge.getIdAsLong());
                        this.text.set(ElementPicker.getPropertyAsString(edge, Tokens._PROPERTIES));
                        for (int i = 0; i < edge.pathCount(); i++) {
                            this.outputs.write(Tokens.SIDEEFFECT, this.longWritable, this.text);
                        }
                        edgesProcessed++;
                    }
                }
                context.getCounter(Counters.OUT_EDGES_PROCESSED).increment(edgesProcessed);
            }
            this.outputs.write(Tokens.GRAPH, NullWritable.get(), value);
        }

        @Override
        public void cleanup(final Mapper<NullWritable, HadoopVertex, LongWritable, Text>.Context context) throws IOException, InterruptedException {
            this.outputs.close();
        }
    }
}
