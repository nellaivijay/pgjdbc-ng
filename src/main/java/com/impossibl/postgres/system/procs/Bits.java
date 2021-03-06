package com.impossibl.postgres.system.procs;

import static com.impossibl.postgres.types.PrimitiveType.Bits;

import java.io.IOException;
import java.util.BitSet;

import org.jboss.netty.buffer.ChannelBuffer;

import com.impossibl.postgres.system.Context;
import com.impossibl.postgres.types.PrimitiveType;
import com.impossibl.postgres.types.Type;



public class Bits extends SimpleProcProvider {

	public Bits() {
		super(new TxtEncoder(), new TxtDecoder(), new BinEncoder(), new BinDecoder(), "bit_", "varbit_");
	}

	static class BinDecoder extends BinaryDecoder {

		public PrimitiveType getInputPrimitiveType() {
			return Bits;
		}
		
		public Class<?> getOutputType() {
			return BitSet.class;
		}

		public BitSet decode(Type type, ChannelBuffer buffer, Context context) throws IOException {

			int length = buffer.readInt();
			if (length == -1) {
				return null;
			}

			int bitCount = buffer.readInt();
			int byteCount = (bitCount + 7) / 8;

			byte[] bytes = new byte[byteCount];
			buffer.readBytes(bytes);

			// Set equivalent bits in bit set (they use reversed encodings so
			// they cannot be just copied in
			BitSet bs = new BitSet(bitCount);
			for (int c = 0; c < bitCount; ++c) {
				bs.set(c, (bytes[c / 8] & (0x80 >> (c % 8))) != 0);
			}

			return bs;
		}

	}

	static class BinEncoder extends BinaryEncoder {

		public Class<?> getInputType() {
			return BitSet.class;
		}

		public PrimitiveType getOutputPrimitiveType() {
			return Bits;
		}
		
		public void encode(Type type, ChannelBuffer buffer, Object val, Context context) throws IOException {

			if (val == null) {

				buffer.writeInt(-1);
			}
			else {

				BitSet bs = (BitSet) val;

				int bitCount = bs.size();
				int byteCount = (bitCount + 7) / 8;

				// Set equivalent bits in byte array (they use reversed encodings so
				// they cannot be just copied in
				byte[] bytes = new byte[byteCount];
				for (int c = 0; c < bitCount; ++c) {
					bytes[c / 8] |= ((0x80 >> (c % 8)) & (bs.get(c) ? 0xff : 0x00));
				}

				buffer.writeInt(bs.size());
				buffer.writeBytes(bytes);
			}

		}

	}

	static class TxtDecoder extends TextDecoder {

		public PrimitiveType getInputPrimitiveType() {
			return Bits;
		}
		
		public Class<?> getOutputType() {
			return BitSet.class;
		}

		public BitSet decode(Type type, CharSequence buffer, Context context) throws IOException {
			
			BitSet bits = new BitSet();
			
			for(int c=0, sz=buffer.length(); c < sz; ++c) {
				
				switch(buffer.charAt(c)) {
				case '0':
					bits.clear(c);
					break;
					
				case '1':
					bits.set(c);					
					break;
					
				default:
					throw new IOException("Invalid bits format");
				}
			}
			
			return bits;
		}

	}

	static class TxtEncoder extends TextEncoder {

		public Class<?> getInputType() {
			return BitSet.class;
		}

		public PrimitiveType getOutputPrimitiveType() {
			return Bits;
		}
		
		public void encode(Type type, StringBuilder buffer, Object val, Context context) throws IOException {			

			BitSet bits = (BitSet) val;
			
			for(int c=0, sz=bits.length(); c < sz; ++c) {
				
				buffer.append(bits.get(c) ? '1' : '0');
			}
			
		}

	}

}
