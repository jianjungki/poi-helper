package com.orange.poi.util;

import com.orange.poi.PoiUnitTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Iterator;

/**
 * 图片处理工具
 *
 * @author 小天
 * @date 2019/6/3 23:29
 */
public class ImageTool {

    private final static Logger logger = LoggerFactory.getLogger(ImageTool.class);
    private final static String jpegNodeName = "javax_imageio_jpeg_image_1.0";
    static Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("JPEG");
    /**
     * jpeg 文件魔数，第 0 位
     */
    public static final byte JPEG_MAGIC_CODE_0           = (byte) 0xFF;
    public static final byte JPEG_MAGIC_CODE_1           = (byte) 0xD8;
    /**
     * 水平和垂直方向的像素密度单位：无单位
     */
    public static final byte JPEG_UNIT_OF_DENSITIES_NONE = 0x00;
    /**
     * 水平和垂直方向的像素密度单位：点数/英寸
     */
    public static final byte JPEG_UNIT_OF_DENSITIES_INCH = 0x01;
    /**
     * 水平和垂直方向的像素密度单位：点数/厘米
     */
    public static final byte JPEG_NIT_OF_DENSITIES_CM    = 0x02;

    public static final byte DPI_120 = 0x78;
    public static final byte DPI_96  = 0x60;
    public static final byte DPI_72  = 0x48;

    /**
     * png 图片 pHYs 块，像素密度单位 / 每米
     */
    public static final int PNG_pHYs_pixelsPerUnit = (int) PoiUnitTool.centimeterToPixel(100);

    /**
     * 读取图片文件
     *
     * @param imgFile 图片文件
     *
     * @return {@link BufferedImage}
     *
     * @throws IOException
     */
    public static BufferedImage readImage(File imgFile) throws IOException {
        InputStream inputStream;
        if ((inputStream = FileUtil.readFile(imgFile)) == null) {
            return null;
        }
        return ImageIO.read(inputStream);
    }


    // Helper method to recursively find a node in the metadata tree
    private static Node findNode(Node rootNode, String nodeName) {
        if (rootNode.getNodeName().equalsIgnoreCase(nodeName)) {
            return rootNode;
        }
        for (int i = 0; i < rootNode.getChildNodes().getLength(); i++) {
            Node found = findNode(rootNode.getChildNodes().item(i), nodeName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 重置图片的像素密度信息（默认重置为 96，只支持 jpg 和 png 图片），以修复 wps 在 win10 下打印图片缺失的 bug
     *
     * @param imageFile 源文件
     *
     * @return 新的文件，null：处理失败
     *
     * @throws IOException
     */
    public static File resetDensity(File imageFile) throws IOException {
        ImageInputStream imageInputStream = ImageIO.createImageInputStream(imageFile);
        if (imageInputStream == null) {
            return null;
        }

        ImageReader reader = getImageReader(imageInputStream);
        if (reader == null) {
            return null;
        }

        reader.setInput(imageInputStream, true, false);

        String exName;
        IIOMetadata metadata;
        try {
            metadata = reader.getImageMetadata(0);
        } catch (IIOException e) {
            logger.error("imageFile={}", imageFile, e);
            return null;
        }
        if (metadata.getNativeMetadataFormatName().equals(jpegNodeName)) {
            Integer resUnits = getResUnits(metadata);
            if (resUnits != null && resUnits != 0) {
                // 已指定了像素密度时，不再继续处理
                return null;
            }
            try {
                resetDensity(metadata);
            } catch (IIOInvalidTreeException e) {
                logger.error("imageFile={}", imageFile, e);
                return null;
            }
            exName = "jpg";
        } else if ("png".equalsIgnoreCase(reader.getFormatName())) {

            String formatName = metadata.getNativeMetadataFormatName();
            Node rootNode = metadata.getAsTree(formatName);
            Node pHYsNode = findNode(rootNode, "pHYs");
            if (pHYsNode != null) {
                // Assuming the attribute exists; error handling omitted for clarity
                Node unitSpecifierNode = pHYsNode.getAttributes().getNamedItem("unitSpecifier");
                if (unitSpecifierNode != null){
                    int unitSpecifier = Integer.parseInt(unitSpecifierNode.getNodeValue());
                    System.out.println("Unit Specifier: " + unitSpecifier);
                    if (unitSpecifier != 0) {
                        // Pixel density has been specified, no further processing needed
                        return null;
                    }
                }

                // Perform your logic here with unitSpecifier
            }

            resetDensityPng(metadata);
            exName = "png";
        } else {
            throw new IllegalArgumentException("不支持的图片格式");
        }

        BufferedImage bufferedImage;
        try {
            bufferedImage = reader.read(0, reader.getDefaultReadParam());
        } finally {
            reader.dispose();
            imageInputStream.close();
        }

        ImageOutputStream imageOutputStream = null;
        ImageWriter imageWriter = null;
        try {
            File dstImgFile = TempFileUtil.createTempFile(exName);

            imageOutputStream = ImageIO.createImageOutputStream(dstImgFile);

            imageWriter = ImageIO.getImageWriter(reader);
            imageWriter.setOutput(imageOutputStream);

            ImageWriteParam writeParam = imageWriter.getDefaultWriteParam();
            if (writeParam instanceof JPEGImageWriteParam) {
                ((JPEGImageWriteParam) writeParam).setOptimizeHuffmanTables(true);
            }
            try {
                imageWriter.write(metadata, new IIOImage(bufferedImage, Collections.emptyList(), metadata), writeParam);
            } catch (NullPointerException e) {
                //有些时候会出现LCMS.getProfileSize出现空指针，因为LCMS单例被注销  原因不明 也无法避免  故try catch下
                logger.error("imageFile={}", imageFile, e);
                return null;
            }
            return dstImgFile;
        } finally {
            if (imageWriter != null) {
                imageWriter.dispose();
            }
            if (imageWriter != null) {
                imageOutputStream.flush();
            }
        }
    }

    private static void resetDensity(IIOMetadata metadata) throws IIOInvalidTreeException {
        final IIOMetadataNode newRootNode = new IIOMetadataNode(jpegNodeName);

        // 方法一
        final IIOMetadataNode mergeJFIFsubNode = new IIOMetadataNode("mergeJFIFsubNode");
        IIOMetadataNode jfifNode = new IIOMetadataNode("jfif");
        jfifNode.setAttribute("majorVersion", null);
        jfifNode.setAttribute("minorVersion", null);
        jfifNode.setAttribute("thumbWidth", null);
        jfifNode.setAttribute("thumbHeight", null);

        // 重置像素密度单位
        jfifNode.setAttribute("resUnits", "1");
        jfifNode.setAttribute("Xdensity", "96");
        jfifNode.setAttribute("Ydensity", "96");
        mergeJFIFsubNode.appendChild(jfifNode);

        newRootNode.appendChild(mergeJFIFsubNode);
        newRootNode.appendChild(new IIOMetadataNode("mergeSequenceSubNode"));
        metadata.mergeTree(jpegNodeName, newRootNode);

        // 方法二
//        final IIOMetadataNode dimensionNode = new IIOMetadataNode("Dimension");
//        final IIOMetadataNode horizontalPixelSizeNode = new IIOMetadataNode("HorizontalPixelSize");
//        horizontalPixelSizeNode.setAttribute("value", String.valueOf(25.4f / 96));
//        final IIOMetadataNode verticalPixelSizeNode = new IIOMetadataNode("VerticalPixelSize");
//        verticalPixelSizeNode.setAttribute("value", String.valueOf(25.4f / 96));
//        dimensionNode.appendChild(horizontalPixelSizeNode);
//        dimensionNode.appendChild(verticalPixelSizeNode);
//        newRootNode.appendChild(dimensionNode);
//        metadata.mergeTree(IIOMetadataFormatImpl.standardMetadataFormatName, newRootNode);
    }

    private static void resetDensityPng(IIOMetadata metadata) throws IIOInvalidTreeException {
        String metadataFormat = IIOMetadataFormatImpl.standardMetadataFormatName;
        IIOMetadataNode root = (IIOMetadataNode)metadata.getAsTree(metadataFormat);

        IIOMetadataNode physNode = new IIOMetadataNode("pHYs");
        physNode.setAttribute("pixelsPerUnitXAxis", Integer.toString(PNG_pHYs_pixelsPerUnit));
        physNode.setAttribute("pixelsPerUnitYAxis", Integer.toString(PNG_pHYs_pixelsPerUnit));
        physNode.setAttribute("unitSpecifier", Integer.toString(1)); // unitSpecifier should be 1 for meters
        physNode.setAttribute("present", Boolean.toString(true));

        Node existingPhysNode = findNode(root, "pHYs");
        if (existingPhysNode != null) {
            root.replaceChild(physNode, existingPhysNode);
        } else {
            root.appendChild(physNode);
        }
        // Now, set the updated metadata back to the ImageWriter
        metadata.setFromTree(metadataFormat, root);

    }

    /**
     * 获取 jpg 图片的像素密度类型
     *
     * @param metadata
     *
     * @return
     */
    private static Integer getResUnits(IIOMetadata metadata) {
        String value = getJfifAttr(metadata, "resUnits");
        if (value == null) {
            return null;
        }
        return Integer.parseInt(value);
    }

    /**
     * 获取 jpg 图片的像素密度类型
     *
     * @param metadata
     *
     * @return
     */
    private static String getJfifAttr(IIOMetadata metadata, String attrName) {
        Node metadataNode = metadata.getAsTree(jpegNodeName);

        if (metadataNode != null) {
            Node child = metadataNode.getFirstChild();
            while (child != null) {
                if (child.getNodeName().equals("JPEGvariety")) {
                    Node subChild = child.getFirstChild();
                    while (subChild != null) {
                        if ("app0JFIF".equals(subChild.getNodeName())) {
                            Node valueNode = subChild.getAttributes().getNamedItem(attrName);
                            if (valueNode != null) {
                                return valueNode.getNodeValue();
                            }
                            break;
                        }
                        subChild = subChild.getNextSibling();
                    }
                    break;
                }
                child = child.getNextSibling();
            }
        }
        return null;
    }

    private static ImageReader getImageReader(ImageInputStream stream) {
        Iterator iter = ImageIO.getImageReaders(stream);
        if (!iter.hasNext()) {
            return null;
        }
        return (ImageReader) iter.next();
    }
    private static boolean  isJPEGReader(ImageReader reader){
        ImageReader nextReader = readers.next();
        return reader.getClass() == nextReader.getClass();
    }

    /**
     * 获取像素密度
     *
     * @param imageFile 源文件
     *
     * @return 像素密度
     *
     * @throws IOException
     */
    public static Integer getDensity(File imageFile) throws IOException {
        ImageInputStream imageInputStream = ImageIO.createImageInputStream(imageFile);
        if (imageInputStream == null) {
            return null;
        }

        ImageReader reader = getImageReader(imageInputStream);
        if (reader == null) {
            return null;
        }
        if (!isJPEGReader(reader)) {
            return null;
        }
        reader.setInput(imageInputStream, true, false);

        IIOMetadata metadata;
        try {
            metadata = reader.getImageMetadata(0);
        } catch (IIOException e) {
            logger.error("imageFile={}", imageFile, e);
            return null;
        }
        if (metadata.getNativeMetadataFormatName().equals(jpegNodeName)) {
            Integer resUnits = getResUnits(metadata);
            if (resUnits == null) {
                return null;
            }
            if (resUnits == 1) {
                // 暂时只支持 resUnits == 1 等情况
                String value = getJfifAttr(metadata, "Xdensity");
                if (value == null) {
                    return null;
                }
                return Integer.parseInt(value);
            }
            return null;
        } else if ("png".equalsIgnoreCase(reader.getFormatName())) {
            String formatName = metadata.getNativeMetadataFormatName();
            Node rootNode = metadata.getAsTree(formatName);
            Node pHYsNode = findNode(rootNode, "pHYs");
            if (pHYsNode != null) {
                // Assuming the attribute exists; error handling omitted for clarity
                Node unitSpecifierNode = pHYsNode.getAttributes().getNamedItem("unitSpecifier");
                if (unitSpecifierNode != null){
                    int unitSpecifier = Integer.parseInt(unitSpecifierNode.getNodeValue());
                    System.out.println("Unit Specifier: " + unitSpecifier);
                    if (unitSpecifier == 1) {
                        // Pixel density has been specified, no further processing needed
                        return unitSpecifier;
                    }
                }

                // Perform your logic here with unitSpecifier
            }
            return null;
        } else {
            throw new IllegalArgumentException("不支持的图片格式");
        }
    }

}
