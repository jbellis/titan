package com.thinkaurelius.titan.hadoop.formats.noop;

import com.thinkaurelius.titan.hadoop.HadoopVertex;

import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import java.io.IOException;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class NoOpRecordWriter extends RecordWriter<NullWritable, HadoopVertex> {

    public NoOpRecordWriter() {
    }

    @Override
    public final void write(final NullWritable key, final HadoopVertex vertex) throws IOException {

    }

    @Override
    public final synchronized void close(final TaskAttemptContext context) throws IOException {
    }
}