import com.thinkaurelius.titan.hadoop.HadoopVertex
import com.thinkaurelius.titan.hadoop.HadoopVertex

import static com.tinkerpop.blueprints.Direction.OUT

/**
 * An example Gremlin/Groovy script that parses a line of the form:
 *   1:2,3,4,5
 * into a HadoopVertex with id 1 and linkedTo-edges to vertices 2, 3, 4, and 5.
 * The provided HadoopVertex in the argument is reusable to avoid object creation.
 * The return boolean denotes whether the read line produced a HadoopVertex that should be added to the stream.
 * If false is returned, this is equivalent to the line being ignored.
 *
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */

def boolean read(HadoopVertex v, String line) {
    parts = line.split(':');
    v.reuse(Long.valueOf(parts[0]))
    if (parts.length == 2) {
        parts[1].split(',').each {
            v.addEdge(OUT, 'linkedTo', Long.valueOf(it));
        }
    }
    return true;
}
