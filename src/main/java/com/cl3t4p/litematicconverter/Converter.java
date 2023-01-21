package com.cl3t4p.litematicconverter;


import lombok.SneakyThrows;
import se.llbit.nbt.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Converter {


	public Converter() {
	}

	@SneakyThrows
	public Map<String,byte[]> litematicToSponge(String baseName,byte[] inputBytes) {
		DataInputStream inStream = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(inputBytes)));
		Tag litematica = CompoundTag.read(inStream).get("");
		inStream.close();
		int dataVersion = litematica.get("MinecraftDataVersion").intValue();

		Map<String,byte[]> byteMap = new HashMap<>();
		CompoundTag regions = litematica.get("Regions").asCompound();
		for (NamedTag regionTag : regions) {
			CompoundTag region = regionTag.asCompound();
			ListTag palette = region.get("BlockStatePalette").asList();

			
			// Litematica dimensions can be negative.
			Tag size = region.get("Size");
			int x = size.get("x").intValue();
			int y = size.get("y").intValue();
			int z = size.get("z").intValue();
			
			// get offset
			Tag position = region.get("Position");
			int offsetx = position.get("x").intValue() + (x < 0 ? x+1 : 0);
			int offsety = position.get("y").intValue() + (y < 0 ? y+1 : 0);
			int offsetz = position.get("z").intValue() + (z < 0 ? z+1 : 0);
			
			// convert blocks
			// use a temporary file to avoid OutOfMemoryError for large schematics

			byte[] weBlocks = transformState(region,Math.abs(x * y * z),palette.size());
			
			int i = 0;
			String[] blockPalette = new String[palette.size()];
			for (SpecificTag blockState : palette) {
				String name = blockState.get("Name").stringValue();
				CompoundTag properties = blockState.get("Properties").asCompound();
				if (!properties.isEmpty()) {
					List<String> propertyNames = new ArrayList<>();
					for (NamedTag property : properties) {
						propertyNames.add(property.name() + "=" + property.unpack().stringValue());
					}
					name += "[" + String.join(",", propertyNames) + "]";
				}
				blockPalette[i++] = name;
			}
			
			// Convert palette
			CompoundTag wePalette = new CompoundTag();
			for (i = 0; i < blockPalette.length; ++i) {
				wePalette.add(blockPalette[i], new IntTag(i));
			}
			
			// Copy tile entity data
			List<CompoundTag> weTileEntities = new ArrayList<>();
			List<String> skip = Arrays.asList("x", "y", "z", "id");
			for (SpecificTag tileEntity : region.get("TileEntities").asList()) {
				CompoundTag liteTileEntity = tileEntity.asCompound();
				CompoundTag weTileEntity = new CompoundTag();
				
				// Litematica uses integer "x", "y", and "z" tags
				// WorldEdit uses one integer array "Pos" tag
				int tx = liteTileEntity.get("x").intValue();
				int ty = liteTileEntity.get("y").intValue();
				int tz = liteTileEntity.get("z").intValue();
				weTileEntity.add("Pos", new IntArrayTag(new int[] {tx, ty, tz}));
				
				// Litematica uses a lowercase "id"
				// WorldEdit uses a capitalized "Id"
				String tid = liteTileEntity.get("id").stringValue();
				weTileEntity.add("Id", new StringTag(tid));
				
				for (NamedTag tileEntityTag : liteTileEntity) {
					String name = tileEntityTag.name();
					if (!skip.contains(name))
						weTileEntity.add(tileEntityTag);
				}
				weTileEntities.add(weTileEntity);
			}
			
			// metadata
			CompoundTag metadata = new CompoundTag();
			metadata.add("WEOffsetX", new IntTag(offsetx));
			metadata.add("WEOffsetY", new IntTag(offsety));
			metadata.add("WEOffsetZ", new IntTag(offsetz));
			
			CompoundTag worldEdit = new CompoundTag();
			worldEdit.add(new NamedTag("Metadata", metadata));
			worldEdit.add(new NamedTag("Palette", wePalette));
			worldEdit.add(new NamedTag("BlockEntities", new ListTag(Tag.TAG_COMPOUND, weTileEntities)));
			worldEdit.add(new NamedTag("DataVersion", new IntTag(dataVersion)));
			worldEdit.add(new NamedTag("Height", new ShortTag((short) Math.abs(y))));
			worldEdit.add(new NamedTag("Length", new ShortTag((short) Math.abs(z))));
			worldEdit.add(new NamedTag("PaletteMax", new IntTag(wePalette.size())));
			worldEdit.add(new NamedTag("Version", new IntTag(2)));
			worldEdit.add(new NamedTag("Width", new ShortTag((short) Math.abs(x))));
			worldEdit.add(new NamedTag("BlockData", new ByteArrayTag(weBlocks)));
			worldEdit.add(new NamedTag("Offset", new IntArrayTag(new int[3])));
			
			CompoundTag worldEditRoot = new CompoundTag();
			worldEditRoot.add("Schematic", worldEdit);
			
			// determine outputFileName
			String output_name = baseName;
			if (output_name.contains(".")) {
				output_name = output_name.substring(0, output_name.lastIndexOf('.'));
			}
			if (regions.size() > 1) {
				output_name += "-" + regionTag.name();
			}
			output_name = output_name.replace(' ', '_');
			
			// make sure directory exists, and write to the provided path
			ByteArrayOutputStream bs = new ByteArrayOutputStream();
			DataOutputStream outStream = new DataOutputStream(new GZIPOutputStream(bs));
			worldEditRoot.write(outStream);
			outStream.close();
			byteMap.put(output_name,bs.toByteArray());
		}
		return byteMap;
	}
	
	private void writeBlock(ByteArrayOutputStream writer, short block){
		int b = block >>> 7;
		if (b == 0) {
			writer.write(block);
		}
		else {
			writer.write(block | 128);
			writer.write(b);
		}
	}

	private byte[] transformState(Tag region,int numBlocks,int palletSize){
		int bitsPerBlock = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palletSize - 1));
		ByteArrayOutputStream writer = new ByteArrayOutputStream();
		long bitmask, bits = 0;
		int i = 0, bitCount = 0;
		for (long num : region.get("BlockStates").longArray()) {
			int remainingBits = bitCount + 64;
			if (bitCount != 0) {
				bitmask = (1L << (bitsPerBlock - bitCount)) - 1;
				long newBits = (num & bitmask) << bitCount;
				bits = bits | newBits;
				num = num >>> (bitsPerBlock - bitCount);
				remainingBits -= bitsPerBlock;
				writeBlock(writer, (short) bits);
				i++;
			}

			bitmask = (1L << bitsPerBlock) - 1;
			while (remainingBits >= bitsPerBlock) {
				bits = num & bitmask;
				num = num >>> bitsPerBlock;
				remainingBits -= bitsPerBlock;
				if (i >= numBlocks)
					break;
				writeBlock(writer, (short) bits);
				i++;
			}
			bits = num;
			bitCount = remainingBits;
		}
		return writer.toByteArray();
	}

}
