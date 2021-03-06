package com.thinkaurelius.titan.hadoop.mapreduce.transform;

import com.thinkaurelius.titan.hadoop.HadoopEdge;
import com.thinkaurelius.titan.hadoop.HadoopPathElement;
import com.thinkaurelius.titan.hadoop.HadoopVertex;
import com.thinkaurelius.titan.hadoop.Tokens;
import com.thinkaurelius.titan.hadoop.mapreduce.util.EmptyConfiguration;
import com.thinkaurelius.titan.hadoop.mapreduce.util.SafeMapperOutputs;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Vertex;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import java.io.IOException;
import java.util.List;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class PathMap {

    public static final String CLASS = Tokens.makeNamespace(PathMap.class) + ".class";

    public enum Counters {
        VERTICES_PROCESSED,
        OUT_EDGES_PROCESSED
    }

    public static Configuration createConfiguration(final Class<? extends Element> klass) {
        final Configuration configuration = new EmptyConfiguration();
        configuration.setClass(CLASS, klass, Element.class);
        configuration.setBoolean(Tokens.TITAN_HADOOP_PIPELINE_TRACK_PATHS, true);
        return configuration;
    }

    public static class Map extends Mapper<NullWritable, HadoopVertex, NullWritable, Text> {

        private boolean isVertex;
        private final Text textWritable = new Text();
        private SafeMapperOutputs outputs;

        @Override
        public void setup(final Mapper.Context context) throws IOException, InterruptedException {
            this.isVertex = context.getConfiguration().getClass(CLASS, Element.class, Element.class).equals(Vertex.class);
            this.outputs = new SafeMapperOutputs(context);
            if (!context.getConfiguration().getBoolean(Tokens.TITAN_HADOOP_PIPELINE_TRACK_PATHS, false))
                throw new IllegalStateException(PathMap.class.getSimpleName() + " requires that paths be enabled");
        }


        @Override
        public void map(final NullWritable key, final HadoopVertex value, final Mapper<NullWritable, HadoopVertex, NullWritable, Text>.Context context) throws IOException, InterruptedException {
            if (this.isVertex && value.hasPaths()) {
                for (final List<HadoopPathElement.MicroElement> path : value.getPaths()) {
                    this.textWritable.set(path.toString());
                    this.outputs.write(Tokens.SIDEEFFECT, NullWritable.get(), this.textWritable);
                }
                context.getCounter(Counters.VERTICES_PROCESSED).increment(1l);
            } else {
                long edgesProcessed = 0;
                for (final Edge e : value.getEdges(Direction.OUT)) {
                    final HadoopEdge edge = (HadoopEdge) e;
                    if (edge.hasPaths()) {
                        for (final List<HadoopPathElement.MicroElement> path : edge.getPaths()) {
                            this.textWritable.set(path.toString());
                            this.outputs.write(Tokens.SIDEEFFECT, NullWritable.get(), this.textWritable);
                        }
                        edgesProcessed++;
                    }
                }
                context.getCounter(Counters.OUT_EDGES_PROCESSED).increment(edgesProcessed);
            }

            this.outputs.write(Tokens.GRAPH, NullWritable.get(), value);
        }

        @Override
        public void cleanup(final Mapper<NullWritable, HadoopVertex, NullWritable, Text>.Context context) throws IOException, InterruptedException {
            this.outputs.close();
        }
    }
}
