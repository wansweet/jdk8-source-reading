package test.java.nio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Random;
import java.util.UUID;

/**
 * @author charpty
 * @since 2018/1/1
 */
public class BuildLargeFile {

	private static final byte[] LINE = "\n".getBytes();

	public static void main(String[] args) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(new File("/tmp/large.file"), "rw");
		FileChannel channel = raf.getChannel();
		ByteBuffer buffer = ByteBuffer.allocate(64);
		int n = 0;
		Random random = new Random();
		random.setSeed(100);
		while (true) {
			String str = UUID.randomUUID().toString();
			buffer.put(str.getBytes());
			buffer.put(str.substring(0, random.nextInt(31)).getBytes());
			buffer.put(LINE);
			buffer.flip();
			channel.write(buffer);
			buffer.clear();
			System.out.println("第" + ++n + "次写入...");
		}
	}

}
