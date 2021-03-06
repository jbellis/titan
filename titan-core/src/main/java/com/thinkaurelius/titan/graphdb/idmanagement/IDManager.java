package com.thinkaurelius.titan.graphdb.idmanagement;


import com.google.common.base.Preconditions;

/**
 * Handles the allocation of ids based on the type of element
 * Responsible for the bit-wise pattern of Titan's internal id scheme.
 *
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class IDManager {

    /**
     *bit mask- Description (+ indicates defined type, * indicates proper & defined type)
     *
     *      0 - * Normal (user created) Vertex
     *      1 - + Hidden
     *     11 -     * Hidden (user created/triggered) Vertex [for later]
     *     01 -     + Schema related vertices
     *    101 -         + Schema Type vertices
     *   0101 -             + Relation Type vertices
     *  00101 -                 + Property Key
     * 000101 -                     * User Property Key
     * 100101 -                     * System Property Key
     *  10101 -                 + Edge Label
     * 010101 -                     * User Edge Label
     * 110101 -                     * System Edge Label
     *   1101 -             Other Type vertices
     *  01101 -                   Vertex Type (future???)
     *    001 -         Non-Type vertices
     *   1001 -             * Generic Schema Vertex
     *   0001 -             Reserved for future
     *
     *
     */
    public enum VertexIDType {
        Vertex {
            @Override
            final long offset() {
                return 1l;
            }

            @Override
            final long suffix() {
                return 0l;
            } // 0b

            @Override
            final boolean isProper() {
                return true;
            }
        },

        Hidden {
            @Override
            final long offset() {
                return 1l;
            }

            @Override
            final long suffix() {
                return 1l;
            } // 1b

            @Override
            final boolean isProper() {
                return false;
            }
        },
        HiddenVertex {
            @Override
            final long offset() {
                return 2l;
            }

            @Override
            final long suffix() {
                return 3l;
            } // 11b

            @Override
            final boolean isProper() {
                return true;
            }
        },
        Schema {
            @Override
            final long offset() {
                return 2l;
            }

            @Override
            final long suffix() {
                return 1l;
            } // 01b

            @Override
            final boolean isProper() {
                return false;
            }
        },
        SchemaType {
            @Override
            final long offset() {
                return 3l;
            }

            @Override
            final long suffix() {
                return 5l;
            } // 101b

            @Override
            final boolean isProper() {
                return false;
            }
        },
        RelationType {
            @Override
            final long offset() {
                return 4l;
            }

            @Override
            final long suffix() {
                return 5l;
            } // 0101b

            @Override
            final boolean isProper() {
                return false;
            }
        },
        PropertyKey {
            @Override
            final long offset() {
                return 5l;
            }

            @Override
            final long suffix() {
                return 5l;
            }    // 00101b

            @Override
            final boolean isProper() {
                return false;
            }
        },
        UserPropertyKey {
            @Override
            final long offset() {
                return 6l;
            }

            @Override
            final long suffix() {
                return 5l;
            }    // 000101b

            @Override
            final boolean isProper() {
                return true;
            }
        },
        SystemPropertyKey {
            @Override
            final long offset() {
                return 6l;
            }

            @Override
            final long suffix() {
                return 37l;
            }    // 100101b

            @Override
            final boolean isProper() {
                return true;
            }
        },
        EdgeLabel {
            @Override
            final long offset() {
                return 5l;
            }

            @Override
            final long suffix() {
                return 21l;
            } // 10101b

            @Override
            final boolean isProper() {
                return false;
            }
        },
        UserEdgeLabel {
            @Override
            final long offset() {
                return 6l;
            }

            @Override
            final long suffix() {
                return 21l;
            } // 010101b

            @Override
            final boolean isProper() {
                return true;
            }
        },
        SystemEdgeLabel {
            @Override
            final long offset() {
                return 6l;
            }

            @Override
            final long suffix() {
                return 53l;
            } // 110101b

            @Override
            final boolean isProper() {
                return true;
            }
        },

        GenericSchemaType {
            @Override
            final long offset() {
                return 4l;
            }

            @Override
            final long suffix() {
                return 9l;
            }    // 1001b

            @Override
            final boolean isProper() {
                return true;
            }
        };

        abstract long offset();

        abstract long suffix();

        abstract boolean isProper();

        public final long addPadding(long count) {
            assert offset()>0;
            Preconditions.checkArgument(count>0 && count<(1l<<(TOTAL_BITS-offset())),"Count out of range for type [%s]: %s",this,count);
            return (count << offset()) | suffix();
        }

        public final long removePadding(long id) {
            return id >>> offset();
        }

        public final boolean is(long id) {
            return (id & ((1l << offset()) - 1)) == suffix();
        }

        public final boolean isSubType(VertexIDType type) {
            return is(type.suffix());
        }
    }

    /**
     * Number of bits that need to be reserved from the type ids for storing additional information during serialization
     */
    public static final int TYPE_LEN_RESERVE = 2;

    /**
     * Total number of bits available to a Titan assigned id
     * We use only 63 bits to make sure that all ids are positive
     *
     * @see com.thinkaurelius.titan.graphdb.database.idhandling.IDHandler#getKey(long)
     */
    private static final long TOTAL_BITS = 63;

    /**
     * Maximum number of bits that can be used for the partition prefix of an id
     */
    private static final long MAX_PARTITION_BITS = 30;
    /**
     * Default number of bits used for the partition prefix. 0 means there is no partition prefix
     */
    private static final long DEFAULT_PARTITION_BITS = 0;

    @SuppressWarnings("unused")
    private final long partitionBits;
    private final long partitionOffset;
    private final long partitionIDBound;

    private final long relationCountBound;
    private final long vertexCountBound;


    public IDManager(long partitionBits) {
        Preconditions.checkArgument(partitionBits >= 0);
        Preconditions.checkArgument(partitionBits <= MAX_PARTITION_BITS,
                "Partition bits can be at most %s bits", MAX_PARTITION_BITS);
        this.partitionBits = partitionBits;

        partitionIDBound = (1l << (partitionBits));

        relationCountBound = partitionBits==0?Long.MAX_VALUE:(1l << (TOTAL_BITS - partitionBits));
        assert VertexIDType.Vertex.offset()>0;
        vertexCountBound = (1l << (TOTAL_BITS - partitionBits - VertexIDType.Vertex.offset()));

        partitionOffset = TOTAL_BITS - partitionBits;
    }

    public IDManager() {
        this(DEFAULT_PARTITION_BITS);
    }

    private static long prefixWithOffset(long id, long prefixid, long prefixOffset, long partitionIDBound) {
        assert partitionIDBound >= 0 && prefixOffset < 64;
        if (id < 0) throw new IllegalArgumentException("ID cannot be negative: " + id);
        if (prefixid < 0) throw new IllegalArgumentException("Prefix ID cannot be negative: " + prefixid);
        if (prefixid == 0) return id;
        Preconditions.checkArgument(prefixid<partitionIDBound,"Prefix ID exceeds limit of: %s",partitionIDBound);
        assert id < (1l << prefixOffset) : "ID is too large for prefix offset: " + id + " ( " + prefixOffset + " )";
        return (prefixid << prefixOffset) | id;
    }


    private long addPartition(long id, long partitionID) {
        assert id > 0;
        assert partitionID >= 0;
        return prefixWithOffset(id, partitionID, partitionOffset, partitionIDBound);
    }

    /*		--- TitanElement id bit format ---
      *  [ 0 | partitionID | count | ID padding ]
     */


    public long getRelationID(long count, long partition) {
        Preconditions.checkArgument(count>0 && count< relationCountBound,"Invalid count for bound: %s", relationCountBound);
        return addPartition(count, partition);
    }


    public long getVertexID(long count, long partition) {
        Preconditions.checkArgument(count>0 && count<vertexCountBound,"Invalid count for bound: %s", vertexCountBound);
        return addPartition(VertexIDType.Vertex.addPadding(count), partition);
    }

    /*

    Temporary ids are negative and don't have partitions

     */

    public static long getTemporaryRelationID(long count) {
        return makeTemporary(count);
    }

    public static long getTemporaryVertexID(VertexIDType type, long count) {
        Preconditions.checkArgument(type.isProper(),"Invalid vertex id type: %s",type);
        return makeTemporary(type.addPadding(count));
    }

    private static long makeTemporary(long id) {
        Preconditions.checkArgument(id>0);
        return (1l<<63) | id; //make negative but preserve bit pattern
    }

    /* --- TitanRelation Type id bit format ---
      *  [ 0 | count | ID padding ]
      *  (there is no partition)
     */

    private static long getSchemaIdBound(VertexIDType type) {
        assert VertexIDType.Schema.isSubType(type) : "Expected schema type but got: " + type;
        assert TYPE_LEN_RESERVE>0;
        return (1l << (TOTAL_BITS - type.offset() - TYPE_LEN_RESERVE));
    }

    private static void checkSchemaTypeId(VertexIDType type, long count) {
        Preconditions.checkArgument(VertexIDType.Schema.is(type.suffix()),"Expected schema vertex but got: %s",type);
        Preconditions.checkArgument(type.isProper(),"Expected proper type but got: %s",type);
        long idBound = getSchemaIdBound(type);
        Preconditions.checkArgument(count > 0 && count < idBound,
                "Invalid id [%s] for type [%s] bound: %s", count, type, idBound);
    }

    public static long getSchemaId(VertexIDType type, long count) {
        checkSchemaTypeId(type,count);
        return type.addPadding(count);
    }

    private static boolean isProperRelationType(long id) {
        return VertexIDType.UserEdgeLabel.is(id) || VertexIDType.SystemEdgeLabel.is(id)
                || VertexIDType.UserPropertyKey.is(id) || VertexIDType.SystemPropertyKey.is(id);
    }

    public static long stripEntireRelationTypePadding(long id) {
        Preconditions.checkArgument(isProperRelationType(id));
        return VertexIDType.UserEdgeLabel.removePadding(id);
    }

    public static long stripRelationTypePadding(long id) {
        Preconditions.checkArgument(isProperRelationType(id));
        return VertexIDType.RelationType.removePadding(id);
    }

    public static long addRelationTypePadding(long id) {
        long typeid = VertexIDType.RelationType.addPadding(id);
        Preconditions.checkArgument(isProperRelationType(typeid));
        return typeid;
    }

    public static boolean isSystemRelationTypeId(long id) {
        return VertexIDType.SystemEdgeLabel.is(id) || VertexIDType.SystemPropertyKey.is(id);
    }

    public long getRelationCountBound() {
        return relationCountBound;
    }

    public long getRelationTypeCountBound() {
        return getSchemaIdBound(VertexIDType.UserEdgeLabel);
    }

    public long getGenericTypeCountBound() {
        return getSchemaIdBound(VertexIDType.GenericSchemaType);
    }

    public long getVertexCountBound() {
        return vertexCountBound;
    }

    public long getPartitionBound() {
        return partitionIDBound;
    }


    public long getPartitionId(long id) {
        //Cannot do this check because it does not apply to edges which are in a different id space
        //Preconditions.checkArgument(!VertexIDType.Schema.is(id), "Schema vertices don't have a partition: %s", id);
        return (id >>> partitionOffset);
    }

    public long isolatePartitionId(long id) {
        return getPartitionId(id) << partitionOffset;
    }

    private final IDInspector inspector = new IDInspector() {

        @Override
        public final boolean isSchemaVertexId(long id) {
            return VertexIDType.Schema.is(id);
        }

        @Override
        public final boolean isRelationTypeId(long id) {
            return VertexIDType.RelationType.is(id);
        }

        @Override
        public final boolean isEdgeLabelId(long id) {
            return VertexIDType.EdgeLabel.is(id);
        }

        @Override
        public final boolean isPropertyKeyId(long id) {
            return VertexIDType.PropertyKey.is(id);
        }

        @Override
        public boolean isSystemRelationTypeId(long id) {
            return IDManager.isSystemRelationTypeId(id);
        }

        @Override
        public final boolean isVertexId(long id) {
            return VertexIDType.Vertex.is(id);
        }

        @Override
        public boolean isGenericSchemaVertexId(long id) {
            return VertexIDType.GenericSchemaType.is(id);
        }

        @Override
        public final long getPartitionId(long id) {
            return IDManager.this.getPartitionId(id);
        }
    };

    public IDInspector getIdInspector() {
        return inspector;
    }

}
