/*
 * Copyright 2019 Dmitry Brant. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dmitrybrant.photo360.rendering;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.nio.charset.Charset;

public class PhotoSphereTools {

    @Nullable
    public static PhotoSphereData getPhotoSphereData(@NonNull byte[] bytes) {
        int bytePtr = 0;
        int segmentStart;
        int segmentType;
        int segmentLength;
        try {
            segmentStart = bytes[bytePtr++] & 0xFF;
            segmentType = bytes[bytePtr++] & 0xFF;

            // check for SOI
            if ((segmentStart != 0xFF) && (segmentType != 0xD8)){
                return null;
            }

            //start reading segments
            while (true) {
                //read bytes until we get an FF
                do {
                    segmentStart = bytes[bytePtr++] & 0xFF;
                } while (segmentStart != 0xFF);

                //read any number of FF bytes
                do {
                    segmentType = bytes[bytePtr++] & 0xFF;
                } while (segmentType == 0xFF);

                if ((segmentType > 0xE0) && (segmentType <= 0xEF)) {
                    //unsupported type of segment...
                }

                //is it the ending segment?
                if (segmentType == 0xD9){
                    break;
                }
                if (segmentType < 0xC0){
                    break;
                }
                if (segmentType == 0xDA){
                    // image scan has started
                }

                //check for segments that don't have a length associated with them
                if ((segmentType >= 0xD0) && (segmentType <= 0xD7)) {
                    // don't need to handle these...
                } else {
                    //read the length of the segment
                    segmentLength = (bytes[bytePtr] << 8) + (bytes[bytePtr + 1] & 0xFF);
                    bytePtr += 2;

                    segmentLength -= 2;
                    if (segmentLength <= 0){
                        continue;
                    }
                    if (segmentLength > 65533){
                        break;
                    }

                    if ((segmentType >= 0xE0) && (segmentType <= 0xEF)) {
                        if (((bytes[bytePtr] == (byte) 'E') && (bytes[bytePtr + 1] == (byte) 'x') && (bytes[bytePtr + 2] == (byte) 'i') && (bytes[bytePtr + 3] == (byte) 'f')) &&
                                (((bytes[bytePtr + 6] == (byte) 'M') && (bytes[bytePtr + 7] == (byte) 'M')) || ((bytes[bytePtr + 6] == (byte) 'I') && (bytes[bytePtr + 7] == (byte) 'I')))) {
                            // process Exif block.
                        }
                        else if (((bytes[bytePtr] == 'M') && (bytes[bytePtr + 1] == 'P') && (bytes[bytePtr + 2] == 'F') && (bytes[bytePtr + 3] == 0)) && (((bytes[bytePtr + 4] == 'M') && (bytes[bytePtr + 5] == 'M')) || ((bytes[bytePtr + 4] == 'I') && (bytes[bytePtr + 5] == 'I'))))
                        {
                            // process MPF block.
                        }
                        else if (segmentType == 0xE1 && bytes[bytePtr] == (byte) 'h' && bytes[bytePtr + 1] == (byte) 't' && bytes[bytePtr + 2] == (byte) 't' && bytes[bytePtr + 3] == (byte) 'p') {
                            // very probably XMP...
                            int zeroPos = 0;
                            for (int i = bytePtr; i < bytePtr + segmentLength; i++) {
                                if (bytes[i] == 0) {
                                    zeroPos = i;
                                    break;
                                }
                            }
                            String nsStr = new String(bytes, bytePtr, zeroPos - bytePtr - 1, Charset.forName("ASCII"));
                            if (nsStr.contains("ns.adobe.com/xap")) {
                                String xmpStr = new String(bytes, zeroPos + 1, segmentLength - (zeroPos - bytePtr) - 1, Charset.forName("UTF-8"));
                                return new PhotoSphereData(xmpStr);
                            }
                        }
                    }

                    bytePtr += segmentLength;
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }


    private static String getSingleAttribute(String xmpStr, String name) {
        int pos = xmpStr.indexOf(name);
        if (pos < 0) {
            return "";
        }
        int q1pos = xmpStr.indexOf("\"", pos);
        int q2pos = xmpStr.indexOf("\"", q1pos + 1);
        return xmpStr.substring(q1pos + 1, q2pos);
    }

    private static String getStringAttribute(String xmpStr, String name) {
        try {
            return getSingleAttribute(xmpStr, name);
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    private static int getIntAttribute(String xmpStr, String name) {
        try {
            return Integer.parseInt(getSingleAttribute(xmpStr, name));
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    private static float getFloatAttribute(String xmpStr, String name) {
        try {
            return Float.parseFloat(getSingleAttribute(xmpStr, name));
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    private static boolean getBooleanAttribute(String xmpStr, String name) {
        try {
            return getSingleAttribute(xmpStr, name).toLowerCase().equals("true");
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    public static class PhotoSphereData {
        public boolean usePanoramaViewer;
        public boolean isPhotoSphere;
        public String projectionType;
        public int fullPanoWidthPixels;
        public int fullPanoHeightPixels;
        public int croppedAreaTopPixels;
        public int croppedAreaLeftPixels;
        public int croppedAreaImageWidthPixels;
        public int croppedAreaImageHeightPixels;
        public float poseHeadingDegrees;

        public PhotoSphereData(@NonNull String xmpStr) {
            usePanoramaViewer = getBooleanAttribute(xmpStr, "GPano:UsePanoramaViewer");
            isPhotoSphere = getBooleanAttribute(xmpStr, "GPano:IsPhotosphere");
            projectionType = getStringAttribute(xmpStr, "GPano:ProjectionType");
            fullPanoWidthPixels = getIntAttribute(xmpStr, "GPano:FullPanoWidthPixels");
            fullPanoHeightPixels = getIntAttribute(xmpStr, "GPano:FullPanoHeightPixels");
            croppedAreaTopPixels = getIntAttribute(xmpStr, "GPano:CroppedAreaTopPixels");
            croppedAreaLeftPixels = getIntAttribute(xmpStr, "GPano:CroppedAreaLeftPixels");
            croppedAreaImageWidthPixels = getIntAttribute(xmpStr, "GPano:CroppedAreaImageWidthPixels");
            croppedAreaImageHeightPixels = getIntAttribute(xmpStr, "GPano:CroppedAreaImageHeightPixels");
            poseHeadingDegrees = getFloatAttribute(xmpStr, "GPano:PoseHeadingDegrees");
        }
    }
}
