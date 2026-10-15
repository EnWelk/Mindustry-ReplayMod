package com.enwelk.replaymod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public abstract class Action {
    public final byte actionType;

    protected Action(byte actionType) {
        this.actionType = actionType;
    }

    protected abstract int dataSize();
    protected abstract void writeData(ByteBuffer buffer);
    protected abstract void readData(ByteBuffer buffer);

    public final int getBufferSize() {
        return 1 + dataSize();
    }

    public final void write(ByteBuffer buffer) {
        buffer.put(actionType);
        writeData(buffer);
    }

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
            default:
                throw new IllegalArgumentException("Unknown action type: " + type);
        }

        action.readData(buffer);
        return action;
    }

    public static ByteBuffer createAction(Action a) {
        ByteBuffer buffer = ByteBuffer
            .allocate(a.getBufferSize())
            .order(ByteOrder.LITTLE_ENDIAN);
        
        a.write(buffer);
        buffer.flip();
        
        return buffer;
    }

    protected static int configSize(byte type) {
        switch (type) {
            case 0: return 0;
            case 1: return 4;
            case 2: return 4;
            case 3: return 2;
            case 4: return 0;
            case 5: return 0;
            default: throw new IllegalArgumentException("Unknown config type: " + type);
        }
    }

    protected static int configSize(byte type, byte[] data) {
        return type == 4 || type == 5
            ? Integer.BYTES + (data == null ? 0 : data.length)
            : configSize(type);
    }

    protected static void writeConfig(ByteBuffer buffer, byte type, int integer, short x, short y, short itemId, byte[] data) {
        switch (type) {
            case 0: break;
            case 1: buffer.putInt(integer); break;
            case 2: buffer.putShort(x); buffer.putShort(y); break;
            case 3: buffer.putShort(itemId); break;
            case 4:
            case 5:
                if (data == null) {
                    throw new IllegalArgumentException("Missing serialized config data");
                }
                buffer.putInt(data.length);
                buffer.put(data);
                break;
            default:
                throw new IllegalArgumentException("Unknown config type: " + type);
        }
    }

    protected static void readConfig(ByteBuffer buffer, Action action) {
        if (action instanceof BuildPlan) {
            BuildPlan a = (BuildPlan) action;
            switch (a.configType) {
                case 0: break;
                case 1: a.configInt = buffer.getInt(); break;
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
                case 1: a.configInt = buffer.getInt(); break;
                case 2: a.configShortX = buffer.getShort(); a.configShortY = buffer.getShort(); break;
                case 3: a.configItemId = buffer.getShort(); break;
                case 4: a.configData = readConfigData(buffer); break;
                case 5: a.configData = readConfigData(buffer); break;
                default: throw new IllegalArgumentException("Unknown config type: " + a.configType);
            }
        }
    }

    private static byte[] readConfigData(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            throw new IllegalArgumentException("Invalid serialized config size: " + length);
        }
        byte[] data = new byte[length];
        buffer.get(data);
        return data;
    }

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
    public static class BuildPlan extends Action {
        public short x, y, blockId;
        public byte rotation, configType;
        public int configInt;
        public short configShortX, configShortY, configItemId;
        public byte[] configData;

        public BuildPlan() {
            super((byte) 1);
        }

        @Override
        protected int dataSize() {
            return 2 + 2 + 1 + 2 + 1 + configSize(configType, configData);
        }

        @Override
        protected void writeData(ByteBuffer b) {
            b.putShort(x);
            b.putShort(y);
            b.put(rotation);
            b.putShort(blockId);
            b.put(configType);
            writeConfig(b, this);
        }

        @Override
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
    public static class BreakPlan extends Action {
        public short x, y;

        public BreakPlan() {
            super((byte) 2);
        }

        @Override
        protected int dataSize() {
            return 2 + 2;
        }

        @Override
        protected void writeData(ByteBuffer b) {
            b.putShort(x);
            b.putShort(y);
        }

        @Override
        protected void readData(ByteBuffer b) {
            x = b.getShort();
            y = b.getShort();
        }
    }

    // Config (id=3): buildId(int), configType(byte), [value]
    public static class Config extends Action {
        public int buildId;
        public byte configType;
        public int configInt;
        public short configShortX, configShortY, configItemId;
        public byte[] configData;

        public Config() {
            super((byte) 3);
        }

        @Override
        protected int dataSize() {
            return 4 + 1 + configSize(configType, configData);
        }

        @Override
        protected void writeData(ByteBuffer b) {
            b.putInt(buildId);
            b.put(configType);
            writeConfig(b, this);
        }

        @Override
        protected void readData(ByteBuffer b) {
            buildId = b.getInt();
            configType = b.get();
            readConfig(b, this);
        }
    }

    // UnitControl (id=4): unitId(int)
    public static class UnitControl extends Action {
        public int unitId;

        public UnitControl() {
            super((byte) 4);
        }

        @Override
        protected int dataSize() {
            return 4;
        }

        @Override
        protected void writeData(ByteBuffer b) {
            b.putInt(unitId);
        }

        @Override
        protected void readData(ByteBuffer b) {
            unitId = b.getInt();
        }
    }

    // UnitClear (id=5): no data
    public static class UnitClear extends Action {
        public UnitClear() {
            super((byte) 5);
        }

        @Override
        protected int dataSize() {
            return 0;
        }

        @Override
        protected void writeData(ByteBuffer b) {}

        @Override
        protected void readData(ByteBuffer b) {}
    }

    // RotateBlock (id=6): buildId(int), direction(byte: 1=CW, 0=CCW)
    public static class RotateBlock extends Action {
        public int buildId;
        public byte direction;

        public RotateBlock() {
            super((byte) 6);
        }

        @Override
        protected int dataSize() {
            return 4 + 1;
        }

        @Override
        protected void writeData(ByteBuffer b) {
            b.putInt(buildId);
            b.put(direction);
        }

        @Override
        protected void readData(ByteBuffer b) {
            buildId = b.getInt();
            direction = b.get();
        }
    }

    // Command (id=7): target type in low two bits (0=position, 1=unit, 2=building),
    // queued flag in bit 2, then targetId, position, unitCount and unitIds.
    public static class Command extends Action {
        public byte cmdType;
        public int targetId;
        public float posX, posY;
        public short unitCount;
        public int[] unitIds = new int[0];

        public Command() {
            super((byte) 7);
        }

        @Override
        protected int dataSize() {
            int count = unitIds == null ? 0 : unitIds.length;
            if (count > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Too many unit IDs: " + count);
            }
            return 1 + 4 + 4 + 4 + 2 + (count * 4);
        }

        @Override
        protected void writeData(ByteBuffer b) {
            int count = unitIds == null ? 0 : unitIds.length;
            if (count > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Too many unit IDs: " + count);
            }
            unitCount = (short) count;
            b.put(cmdType);
            b.putInt(targetId);
            b.putFloat(posX);
            b.putFloat(posY);
            b.putShort(unitCount);
            if (unitIds != null) {
                for (int id : unitIds) {
                    b.putInt(id);
                }
            }
        }

        @Override
        protected void readData(ByteBuffer b) {
            cmdType = b.get();
            targetId = b.getInt();
            posX = b.getFloat();
            posY = b.getFloat();
            unitCount = b.getShort();
            if (unitCount < 0 || unitCount > b.remaining() / 4) {
                throw new IllegalArgumentException("Invalid unit count: " + unitCount);
            }
            unitIds = new int[unitCount];
            for (int i = 0; i < unitCount; i++) {
                unitIds[i] = b.getInt();
            }
        }
    }

    // PayloadAction (id=8): payloadActionType(byte: 1=Pickup, 2=Drop), targetType(byte: 1=Unit, 2=Building, 3=Position), targetId(int), dropX(float), dropY(float)
    public static class PayloadAction extends Action {
        public byte payloadActionType, targetType;
        public int targetId;
        public float dropX, dropY;

        public PayloadAction() {
            super((byte) 8);
        }

        @Override
        protected int dataSize() {
            return 1 + 1 + 4 + 4 + 4;
        }

        @Override
        protected void writeData(ByteBuffer b) {
            b.put(payloadActionType);
            b.put(targetType);
            b.putInt(targetId);
            b.putFloat(dropX);
            b.putFloat(dropY);
        }

        @Override
        protected void readData(ByteBuffer b) {
            payloadActionType = b.get();
            targetType = b.get();
            targetId = b.getInt();
            dropX = b.getFloat();
            dropY = b.getFloat();
        }
    }

    // TileTap (id=9): x(short), y(short)
    public static class TileTap extends Action {
        public short x, y;

        public TileTap() {
            super((byte) 9);
        }

        @Override
        protected int dataSize() {
            return 2 + 2;
        }

        @Override
        protected void writeData(ByteBuffer b) {
            b.putShort(x);
            b.putShort(y);
        }

        @Override
        protected void readData(ByteBuffer b) {
            x = b.getShort();
            y = b.getShort();
        }
    }

    // BuildPause (id=10): isBuilding(byte: 1=active, 0=paused)
    public static class BuildPause extends Action {
        public byte isBuilding;

        public BuildPause() {
            super((byte) 10);
        }

        @Override
        protected int dataSize() {
            return 1;
        }

        @Override
        protected void writeData(ByteBuffer b) {
            b.put(isBuilding);
        }

        @Override
        protected void readData(ByteBuffer b) {
            isBuilding = b.get();
        }
    }

    // ModeChange (id=11): PlaceMode ordinal(byte)
    public static class ModeChange extends Action {
        public byte mode;

        public ModeChange() {
            super((byte) 11);
        }

        @Override
        protected int dataSize() {
            return 1;
        }

        @Override
        protected void writeData(ByteBuffer b) {
            b.put(mode);
        }

        @Override
        protected void readData(ByteBuffer b) {
            mode = b.get();
        }
    }
}