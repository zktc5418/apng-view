package com.github.sahasbhop.apngview;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import ar.com.hjg.pngj.ChunkReader;
import ar.com.hjg.pngj.ChunkSeqReaderPng;
import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.PngHelperInternal;
import ar.com.hjg.pngj.PngReader;
import ar.com.hjg.pngj.PngjException;
import ar.com.hjg.pngj.chunks.ChunkHelper;
import ar.com.hjg.pngj.chunks.ChunkRaw;
import ar.com.hjg.pngj.chunks.PngChunk;
import ar.com.hjg.pngj.chunks.PngChunkACTL;
import ar.com.hjg.pngj.chunks.PngChunkFCTL;
import ar.com.hjg.pngj.chunks.PngChunkFDAT;
import ar.com.hjg.pngj.chunks.PngChunkIDAT;
import ar.com.hjg.pngj.chunks.PngChunkIEND;
import ar.com.hjg.pngj.chunks.PngChunkIHDR;

/**
 * This is low level, it does not use PngReaderApgn
 * 
 * Extracts animation frames from APGN file to several PNG files<br>
 * Low level, very efficient. Does not compose frames<br>
 * Warning: this writes lots of files, in the same dir as the original PNGs.<br>
 * Options:<br>
 * 		-q: quiet mode<br>
 * Accepts paths in the form 'mypath/*' (all pngs in dir) or 'mypath/**' (idem recursive)<br>
 */
public class ApngExtractFrames {

  public boolean quietMode;
  public List<File> listpng;

  public void doit() {
    for (File file : listpng) {
      try {
        int nf = process(file);
        if (!quietMode) {
          if (nf > 0)
            System.out.printf("%s APNG processed: %d frames extracted \n", file, nf);
          else
            System.out.printf("%s is not APNG \n", file);
        }
      } catch (Exception e) {
        System.err.println("Fatal error: " + e.getMessage());
        e.printStackTrace();
        System.exit(1);
      }
    }
  }

  static class PngReaderBuffered extends PngReader {
    private File orig;

    public PngReaderBuffered(File file) {
      super(file);
      this.orig = file;
    }

    FileOutputStream fo = null;
    File dest;
    ImageInfo frameInfo;
    int numframe = -1;

    @Override
    protected ChunkSeqReaderPng createChunkSeqReader() {
      return new ChunkSeqReaderPng(false) {
        @Override
        public boolean shouldSkipContent(int len, String id) {
          return false; // we dont skip anything!
        }

        @Override
        protected boolean isIdatKind(String id) {
          return false; // dont treat idat as special, jsut buffer it as is
        }

        @Override
        protected void postProcessChunk(ChunkReader chunkR) {
          super.postProcessChunk(chunkR);
          try {
            String id = chunkR.getChunkRaw().id;
            PngChunk lastChunk = chunksList.getChunks().get(chunksList.getChunks().size() - 1);
            if (id.equals(PngChunkFCTL.ID)) {
              numframe++;
              frameInfo = ((PngChunkFCTL) lastChunk).getEquivImageInfo();
              startNewFile();
            }
            if (id.equals(PngChunkFDAT.ID) || id.equals(PngChunkIDAT.ID)) {
              if (id.equals(PngChunkIDAT.ID)) {
                // copy IDAT as is (only if file is open == if FCTL previous == if IDAT is part of the animation
                if (fo != null)
                  chunkR.getChunkRaw().writeChunk(fo);
              } else {
                // copy fDAT as IDAT, trimming the first 4 bytes
                ChunkRaw crawi =
                    new ChunkRaw(chunkR.getChunkRaw().len - 4, ChunkHelper.b_IDAT, true);
                System.arraycopy(chunkR.getChunkRaw().data, 4, crawi.data, 0, crawi.data.length);
                crawi.writeChunk(fo);
              }
              chunkR.getChunkRaw().data = null; // be kind, release memory
            }
            if (id.equals(PngChunkIEND.ID)) {
              if (fo != null)
                endFile(); // end last file
            }
          } catch (Exception e) {
            throw new PngjException(e);
          }
        }
      };
    }

    private void startNewFile() throws Exception {
      if (fo != null)
        endFile();
      dest = createOutputName();
      fo = new FileOutputStream(dest);
      fo.write(PngHelperInternal.getPngIdSignature());
      PngChunkIHDR ihdr = new PngChunkIHDR(frameInfo);
      ihdr.createRawChunk().writeChunk(fo);
      for (PngChunk chunk : getChunksList(false).getChunks()) {// copy all except actl and fctl, until IDAT
        String id = chunk.id;
        if (id.equals(PngChunkIHDR.ID) || id.equals(PngChunkFCTL.ID) || id.equals(PngChunkACTL.ID))
          continue;
        if (id.equals(PngChunkIDAT.ID))
          break;
        chunk.getRaw().writeChunk(fo);
      }
    }

    private void endFile() throws IOException {
      new PngChunkIEND(null).createRawChunk().writeChunk(fo);
      fo.close();
      fo = null;
    }

    private File createOutputName() {
    	return new File(orig.getParent(), getFileName(orig, numframe));
    }
  }
  
  public static String getFileName(File originalFile, int numFrame) {
		String filename = originalFile.getName();
		String baseName = FilenameUtils.getBaseName(filename);
		String extension = FilenameUtils.getExtension(filename);
		return String.format(Locale.ENGLISH, "%s_%03d.%s", baseName, numFrame, extension);
  }

  /**
   * reads a APNG file and tries to split it into its frames - low level! Returns number of animation frames extracted
   */
  public static int process(final File orig) {
    // we extend PngReader, to have a custom behavior: load all chunks opaquely, buffering all, and react to some
    // special chnks
    PngReaderBuffered pngr = new PngReaderBuffered(orig);
    pngr.end(); // read till end - this consumes all the input stream and does all!
    return pngr.numframe + 1;
  }

}