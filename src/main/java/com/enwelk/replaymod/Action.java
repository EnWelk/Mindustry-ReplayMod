package com.enwelk.replaymod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.enwelk.replaymod.util.VarInt;

/** Stores one recorded player action in its compact replay format. */
public abstract class Action {
    public final byte actionType;

    /** Creates an action with its serialized type identifier. */
    protected Action(byte actionType) {
        this.actionType = actionType;
    }

    /** Returns the number of bytes in this action's payload. */
    protected abstract int dataSize();

    /** Writes this action's payload to a little-endian buffer. */
    protected abstract void writeData(ByteBuffer buffer);

    /** Reads this action's payload from a little-endian buffer. */
    protected abstract void readData(ByteBuffer buffer);

    /** Returns the full serialized size, including the type byte. */
    public final int getBufferSize() {
        return 1 + dataSize();
    }

    /** Writes the type and payload to the target buffer. */
    public final void write(ByteBuffer buffer) {
        buffer.put(actionType);
        writeData(buffer);
    }

    /** Reads and constructs one action from its type byte and payload. */
    public static Action read(ByteBuffer buffer) {
        byte type = buffer.get();

        Action action;
        switch (type) {
            case 1: action = new BuildPlan(); break;
            case 2: action = new BreakPlan(); break;
            case 3: action = new Config(); break;
            case 4: action = new UnitControl(); break;
            case 5: action = new UnitClear(); break;
            case 6: action = new RotateBlock(); break;
            case 7: action = new Command(); break;
            case 8: action = new PayloadAction(); break;
            case 9: action = new TileTap(); break;
            case 10: action = new BuildPause(); break;
            case 11: action = new ModeChange(); break;
            case 12: action = new Teleport(); break;
            default:
                throw new IllegalArgumentException("Unknown action type: " + type);
        }

        action.readData(buffer);
        return action;
    }

    /** Serializes an action into a flipped little-endian buffer. */
    public static ByteBuffer createAction(Action a) {
        ByteBuffer buffer = ByteBuffer
            .allocate(a.getBufferSize())
            .order(ByteOrder.LITTLE_ENDIAN);
        
        a.write(buffer);
        buffer.flip();
        
        return buffer;
    }

    /** Returns the size of an encoded config value. */
    protected static int configSize(byte type, int integer, byte[] data) {
        switch (type) {
            case 0: return 0;
            case 1: return VarInt.size(zigZag(integer));
            case 2: return 4;
            case 3: return 2;
            case 4:
            case 5:
                if (data == null) throw new IllegalArgumentException("Missing serialized config data");
                return Math.addExact(VarInt.size(data.length), data.length);
            default: throw new IllegalArgumentException("Unknown config type: " + type);
        }
    }

    private static int zigZag(int value) {
        return (value << 1) ^ (value >> 31);
    }

    /** Writes a typed config value in the replay format. */
    protected static void writeConfig(ByteBuffer buffer, byte type, int integer, short x, short y, short itemId, byte[] data) {
        switch (type) {
            case 0: break;
            case 1: VarInt.writeSigned(buffer, integer); break;
            case 2: buffer.putShort(x); buffer.putShort(y); break;
            case 3: buffer.putShort(itemId); break;
            case 4:
            case 5:
                if (data == null) {
                    throw new IllegalArgumentException("Missing serialized config data");
                }
                VarInt.write(buffer, data.length);
                buffer.put(data);
                break;
            default:
                throw new IllegalArgumentException("Unknown config type: " + type);
        }
    }

    /** Reads a typed config value into its action fields. */
    protected static void readConfig(ByteBuffer buffer, Action action) {
        if (action instanceof BuildPlan) {
            BuildPlan a = (BuildPlan) action;
            switch (a.configType) {
                case 0: break;
                case 1: a.configInt = VarInt.readSigned(buffer); break;
                case 2: a.configShortX = buffer.getShort(); a.configShortY = buffer.getShort(); break;
                case 3: a.configItemId = buffer.getShort(); break;
                case 4: a.configData = readConfigData(buffer); break;
                case 5: a.configData = readConfigData(buffer); break;
                default: throw new IllegalArgumentException("Unknown config type: " + a.configType);
            }
        } else if (action instanceof Config) {
            Config a = (Config) action;
            switch (a.configType) {
                case 0: break;
                case 1: a.configInt = VarInt.readSigned(buffer); break;
                case 2: a.configShortX = buffer.getShort(); a.configShortY = buffer.getShort(); break;
                case 3: a.configItemId = buffer.getShort(); break;
                case 4: a.configData = readConfigData(buffer); break;
                case 5: a.configData = readConfigData(buffer); break;
                default: throw new IllegalArgumentException("Unknown config type: " + a.configType);
            }
        }
    }

    /** Reads length-prefixed serialized config bytes. */
    private static byte[] readConfigData(ByteBuffer buffer) {
        int length = VarInt.read(buffer);
        if (length < 0 || length > buffer.remaining()) {
            throw new IllegalArgumentException("Invalid serialized config size: " + length);
        }
        byte[] data = new byte[length];
        buffer.get(data);
        return data;
    }

    /** Writes config fields from a build-plan or config action. */
    protected static void writeConfig(ByteBuffer buffer, Action action) {
        if (action instanceof BuildPlan) {
            BuildPlan a = (BuildPlan) action;
            writeConfig(buffer, a.configType, a.configInt, a.configShortX, a.configShortY, a.configItemId, a.configData);
        } else if (action instanceof Config) {
            Config a = (Config) action;
            writeConfig(buffer, a.configType, a.configInt, a.configShortX, a.configShortY, a.configItemId, a.configData);
        }
    }

    // BuildPlan (id=1): x(short), y(short), rotation(byte), blockId(short), configType(byte), [config]
    // configType 4 stores a TypeIO-serialized value preceded by its byte length.
    /** Records adding or removing a planned block. */
    public static class BuildPlan extends Action {
        public short x, y, blockId;
        public byte rotation, configType;
        public int configInt;
        public short configShortX, configShortY, configItemId;
        public byte[] configData;

        /** Creates a build-plan action. */
        public BuildPlan() {
            super((byte) 1);
        }

        @Override
        /** Returns the size of the build-plan payload. */
        protected int dataSize() {
            return Math.addExact(
                2 + 2 + 1 + 2 + 1,
                configSize(configType, configInt, configData)
            );
        }

        @Override
        /** Writes the plan coordinates, block, rotation, and config. */
        protected void writeData(ByteBuffer b) {
            b.putShort(x);
            b.putShort(y);
            b.put(rotation);
            b.putShort(blockId);
            b.put(configType);
            writeConfig(b, this);
        }

        @Override
        /** Reads the plan coordinates, block, rotation, and config. */
        protected void readData(ByteBuffer b) {
            x = b.getShort();
            y = b.getShort();
            rotation = b.get();
            blockId = b.getShort();
            configType = b.get();
            readConfig(b, this);
        }
    }

    // BreakPlan (id=2): x(short), y(short)
    /** Records cancelling a planned block at a tile. */
    public static class BreakPlan extends Action {
        public short x, y;

        /** Creates a break-plan action. */
        public BreakPlan() {
            super((byte) 2);
        }

        @Override
        /** Returns the coordinate payload size. */
        protected int dataSize() {
            return 2 + 2;
        }

        @Override
        /** Writes the tile coordinates. */
        protected void writeData(ByteBuffer b) {
            b.putShort(x);
            b.putShort(y);
        }

        @Override
        /** Reads the tile coordinates. */
        protected void readData(ByteBuffer b) {
            x = b.getShort();
            y = b.getShort();
        }
    }

    // Config (id=3): buildId(signed varint), configType(byte), [value]
    /** Records a configuration change to a building. */
    public static class Config extends Action {
        public int buildId;
        public byte configType;
        public int configInt;
        public short configShortX, configShortY, configItemId;
        public byte[] configData;

        /** Creates a building-config action. */
        public Config() {
            super((byte) 3);
        }

        @Override
        /** Returns the size of the building ID and config payload. */
        protected int dataSize() {
            return Math.addExact(
                Math.addExact(VarInt.size(zigZag(buildId)), 1),
                configSize(configType, configInt, configData)
            );
        }

        @Override
        /** Writes the building ID and config value. */
        protected void writeData(ByteBuffer b) {
            VarInt.writeSigned(b, buildId);
            b.put(configType);
            writeConfig(b, this);
        }

        @Override
        /** Reads the building ID and config value. */
        protected void readData(ByteBuffer b) {
            buildId = VarInt.readSigned(b);
            configType = b.get();
            readConfig(b, this);
        }
    }

    // UnitControl (id=4): unitId(varint)
    /** Records transferring control to a unit. */
    public static class UnitControl extends Action {
        public int unitId;

        /** Creates a unit-control action. */
        public UnitControl() {
            super((byte) 4);
        }

        @Override
        /** Returns the unit ID payload size. */
        protected int dataSize() {
            if (unitId < 0) throw new IllegalArgumentException("Negative unit ID: " + unitId);
            return VarInt.size(unitId);
        }

        @Override
        /** Writes the controlled unit ID. */
        protected void writeData(ByteBuffer b) {
            if (unitId < 0) throw new IllegalArgumentException("Negative unit ID: " + unitId);
            VarInt.write(b, unitId);
        }

        @Override
        /** Reads the controlled unit ID. */
        protected void readData(ByteBuffer b) {
            unitId = VarInt.read(b);
        }
    }

    // UnitClear (id=5): no data
    /** Records releasing control of a unit. */
    public static class UnitClear extends Action {
        /** Creates a unit-clear action. */
        public UnitClear() {
            super((byte) 5);
        }

        @Override
        /** Returns zero because this action has no payload. */
        protected int dataSize() {
            return 0;
        }

        @Override
        /** Writes no payload for this action. */
        protected void writeData(ByteBuffer b) {}

        @Override
        /** Reads no payload for this action. */
        protected void readData(ByteBuffer b) {}
    }

    // RotateBlock (id=6): buildId(signed varint), direction(byte: 1=CW, 0=CCW)
    /** Records rotating a building. */
    public static class RotateBlock extends Action {
        public int buildId;
        public byte direction;

        /** Creates a building-rotation action. */
        public RotateBlock() {
            super((byte) 6);
        }

        @Override
        /** Returns the building ID and direction payload size. */
        protected int dataSize() {
            return VarInt.size(zigZag(buildId)) + 1;
        }

        @Override
        /** Writes the building ID and rotation direction. */
        protected void writeData(ByteBuffer b) {
            VarInt.writeSigned(b, buildId);
            b.put(direction);
        }

        @Override
        /** Reads the building ID and rotation direction. */
        protected void readData(ByteBuffer b) {
            buildId = VarInt.readSigned(b);
            direction = b.get();
        }
    }

    // Command (id=7): target type in low two bits (0=position, 1=unit, 2=building),
    // queued flag in bit 2, then signed-varint targetId, position, varint unitCount and unitIds.
    /** Records a unit command and its selected units. */
    public static class Command extends Action {
        public byte cmdType;
        public int targetId;
        public float posX, posY;
        public int unitCount;
        public int[] unitIds = new int[0];

        /** Creates a unit-command action. */
        public Command() {
            super((byte) 7);
        }

        @Override
        /** Returns the command payload size and validates its unit count. */
        protected int dataSize() {
            int count = unitIds == null ? 0 : unitIds.length;
            if (count > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Too many unit IDs: " + count);
            }
            int size = 1 + VarInt.size(zigZag(targetId)) + 4 + 4 +
                VarInt.size(count);
            for (int id : unitIds == null ? new int[0] : unitIds) {
                if (id < 0) throw new IllegalArgumentException("Negative unit ID: " + id);
                size = Math.addExact(size, VarInt.size(id));
            }
            return size;
        }

        @Override
        /** Writes command target, position, and selected unit IDs. */
        protected void writeData(ByteBuffer b) {
            int count = unitIds == null ? 0 : unitIds.length;
            if (count > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Too many unit IDs: " + count);
            }
            unitCount = count;
            b.put(cmdType);
            VarInt.writeSigned(b, targetId);
            b.putFloat(posX);
            b.putFloat(posY);
            VarInt.write(b, unitCount);
            if (unitIds != null) {
                for (int id : unitIds) {
                    if (id < 0) throw new IllegalArgumentException("Negative unit ID: " + id);
                    VarInt.write(b, id);
                }
            }
        }

        @Override
        /** Reads command target, position, and selected unit IDs. */
        protected void readData(ByteBuffer b) {
            cmdType = b.get();
            targetId = VarInt.readSigned(b);
            posX = b.getFloat();
            posY = b.getFloat();
            unitCount = VarInt.read(b);
            if (unitCount < 0 || unitCount > Short.MAX_VALUE || unitCount > b.remaining()) {
                throw new IllegalArgumentException("Invalid unit count: " + unitCount);
            }
            unitIds = new int[unitCount];
            for (int i = 0; i < unitCount; i++) {
                unitIds[i] = VarInt.read(b);
            }
        }
    }

    // PayloadAction (id=8): action type, target type, signed-varint target ID, drop position.
    /** Records picking up or dropping a payload. */
    public static class PayloadAction extends Action {
        public byte payloadActionType, targetType;
        public int targetId;
        public float dropX, dropY;

        /** Creates a payload action. */
        public PayloadAction() {
            super((byte) 8);
        }

        @Override
        /** Returns the fixed payload-action size. */
        protected int dataSize() {
            return 1 + 1 + 4 + 4 + 4;
        }

        @Override
        /** Writes the payload target and drop coordinates. */
        protected void writeData(ByteBuffer b) {
            b.put(payloadActionType);
            b.put(targetType);
            VarInt.writeSigned(b, targetId);
            b.putFloat(dropX);
            b.putFloat(dropY);
        }

        @Override
        /** Reads the payload target and drop coordinates. */
        protected void readData(ByteBuffer b) {
            payloadActionType = b.get();
            targetType = b.get();
            targetId = VarInt.readSigned(b);
            dropX = b.getFloat();
            dropY = b.getFloat();
        }
    }

    // TileTap (id=9): x(short), y(short)
    /** Records tapping a tile. */
    public static class TileTap extends Action {
        public short x, y;

        /** Creates a tile-tap action. */
        public TileTap() {
            super((byte) 9);
        }

        @Override
        /** Returns the tile-coordinate payload size. */
        protected int dataSize() {
            return 2 + 2;
        }

        @Override
        /** Writes the tile coordinates. */
        protected void writeData(ByteBuffer b) {
            b.putShort(x);
            b.putShort(y);
        }

        @Override
        /** Reads the tile coordinates. */
        protected void readData(ByteBuffer b) {
            x = b.getShort();
            y = b.getShort();
        }
    }

    // BuildPause (id=10): isBuilding(byte: 1=active, 0=paused)
    /** Records whether automatic building is active. */
    public static class BuildPause extends Action {
        public byte isBuilding;

        /** Creates a build-pause action. */
        public BuildPause() {
            super((byte) 10);
        }

        @Override
        /** Returns the one-byte state payload size. */
        protected int dataSize() {
            return 1;
        }

        @Override
        /** Writes the automatic-building state. */
        protected void writeData(ByteBuffer b) {
            b.put(isBuilding);
        }

        @Override
        /** Reads the automatic-building state. */
        protected void readData(ByteBuffer b) {
            isBuilding = b.get();
        }
    }

    // ModeChange (id=11): PlaceMode ordinal(byte)
    /** Records a change to the placement mode. */
    public static class ModeChange extends Action {
        public byte mode;

        /** Creates a placement-mode action. */
        public ModeChange() {
            super((byte) 11);
        }

        @Override
        /** Returns the one-byte mode payload size. */
        protected int dataSize() {
            return 1;
        }

        @Override
        /** Writes the placement-mode ordinal. */
        protected void writeData(ByteBuffer b) {
            b.put(mode);
        }

        @Override
        /** Reads the placement-mode ordinal. */
        protected void readData(ByteBuffer b) {
            mode = b.get();
        }
    }

    // Teleport (id=12): x(float), y(float)
    /** Records an absolute player teleport. */
    public static class Teleport extends Action {
        public float x, y;

        /** Creates a player-teleport action. */
        public Teleport() {
            super((byte) 12);
        }

        @Override
        protected int dataSize() {
            return Float.BYTES * 2;
        }

        @Override
        protected void writeData(ByteBuffer b) {
            b.putFloat(x);
            b.putFloat(y);
        }

        @Override
        protected void readData(ByteBuffer b) {
            x = b.getFloat();
            y = b.getFloat();
        }
    }
}


