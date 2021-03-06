/*******************************************************************************
 * Copyright (c) 2016 Weasis Team and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 *******************************************************************************/
package org.weasis.dicom.codec;

import java.awt.image.DataBuffer;
import java.awt.image.DataBufferUShort;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.renderable.ParameterBlock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.media.jai.Histogram;
import javax.media.jai.JAI;
import javax.media.jai.LookupTableJAI;
import javax.media.jai.OpImage;
import javax.media.jai.ROI;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.FormatDescriptor;
import javax.media.jai.operator.LookupDescriptor;
import javax.media.jai.operator.RescaleDescriptor;

import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.MathUtil;
import org.weasis.core.api.image.LutShape;
import org.weasis.core.api.image.PseudoColorOp;
import org.weasis.core.api.image.WindowOp;
import org.weasis.core.api.image.op.ImageStatisticsDescriptor;
import org.weasis.core.api.image.util.ImageToolkit;
import org.weasis.core.api.image.util.LayoutUtil;
import org.weasis.core.api.image.util.Unit;
import org.weasis.core.api.media.data.ImageElement;
import org.weasis.core.api.media.data.SoftHashMap;
import org.weasis.core.api.media.data.TagReadable;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.api.util.LangUtil;
import org.weasis.dicom.codec.display.PresetWindowLevel;
import org.weasis.dicom.codec.display.WindowAndPresetsOp;
import org.weasis.dicom.codec.geometry.GeometryOfSlice;
import org.weasis.dicom.codec.utils.DicomImageUtils;
import org.weasis.dicom.codec.utils.LutParameters;

public class DicomImageElement extends ImageElement {

    private static final Logger LOGGER = LoggerFactory.getLogger(DicomImageElement.class);

    private static final SoftHashMap<LutParameters, LookupTableJAI> LUT_Cache = new SoftHashMap<>();

    private volatile List<PresetWindowLevel> windowingPresetCollection = null;
    private volatile Collection<LutShape> lutShapeCollection = null;

    public DicomImageElement(DcmMediaReader mediaIO, Object key) {
        super(mediaIO, key);

        initPixelConfiguration();
    }

    public void initPixelConfiguration() {
        this.pixelSizeX = 1.0;
        this.pixelSizeY = 1.0;
        this.pixelSpacingUnit = Unit.PIXEL;

        double[] val = null;
        String modality = TagD.getTagValue(mediaIO, Tag.Modality, String.class);
        if (!"SC".equals(modality) && !"OT".equals(modality)) { //$NON-NLS-1$ //$NON-NLS-2$
            // Physical distance in mm between the center of each pixel (ratio in mm)
            val = TagD.getTagValue(mediaIO, Tag.PixelSpacing, double[].class);
            if (val == null || val.length != 2) {
                val = TagD.getTagValue(mediaIO, Tag.ImagerPixelSpacing, double[].class);
                // Follows D. Clunie recommendations
                pixelSizeCalibrationDescription = val == null ? null : Messages.getString("DicomImageElement.detector"); //$NON-NLS-1$

            } else {
                pixelSizeCalibrationDescription =
                    TagD.getTagValue(mediaIO, Tag.PixelSpacingCalibrationDescription, String.class);
            }
            if (val == null || val.length != 2) {
                val = TagD.getTagValue(mediaIO, Tag.NominalScannedPixelSpacing, double[].class);
            }

            if (val != null && val.length == 2 && val[0] > 0.0 && val[1] > 0.0) {
                /*
                 * Pixel Spacing = Row Spacing \ Column Spacing => (Y,X) The first value is the row spacing in mm, that
                 * is the spacing between the centers of adjacent rows, or vertical spacing. Pixel Spacing must be
                 * always positive, but some DICOMs have negative values
                 */
                setPixelSize(val[1], val[0]);
                pixelSpacingUnit = Unit.MILLIMETER;
            }

            // DICOM $C.11.1.1.2 Modality LUT and Rescale Type
            // Specifies the units of the output of the Modality LUT or rescale operation.
            // Defined Terms:
            // OD = The number in the LUT represents thousands of optical density. That is, a value of
            // 2140 represents an optical density of 2.140.
            // HU = Hounsfield Units (CT)
            // US = Unspecified
            // Other values are permitted, but are not defined by the DICOM Standard.
            pixelValueUnit = TagD.getTagValue(this, Tag.RescaleType, String.class);
            if (pixelValueUnit == null) {
                // For some other modalities like PET
                pixelValueUnit = TagD.getTagValue(this, Tag.Units, String.class);
            }
            if (pixelValueUnit == null && "CT".equals(modality)) { //$NON-NLS-1$
                pixelValueUnit = "HU"; //$NON-NLS-1$
            }

        }

        if (val == null) {
            int[] aspects = TagD.getTagValue(mediaIO, Tag.PixelAspectRatio, int[].class);
            if (aspects != null && aspects.length == 2 && aspects[0] != aspects[1]) {
                /*
                 * Set the Pixel Aspect Ratio to the pixel size of the image to stretch the rendered image (for having
                 * square pixel on the display image)
                 */
                if (aspects[1] < aspects[0]) {
                    setPixelSize(1.0, (double) aspects[0] / (double) aspects[1]);
                } else {
                    setPixelSize((double) aspects[1] / (double) aspects[0], 1.0);
                }
            }
        }
    }

    /**
     * @return return the min value after modality pixel transformation and after pixel padding operation if padding
     *         exists.
     */
    @Override
    public double getMinValue(TagReadable tagable, boolean pixelPadding) {
        // Computes min and max as slope can be negative
        return Math.min(pixel2mLUT(super.getMinValue(tagable, pixelPadding), tagable, pixelPadding),
            pixel2mLUT(super.getMaxValue(tagable, pixelPadding), tagable, pixelPadding));
    }

    /**
     * @return return the max value after modality pixel transformation and after pixel padding operation if padding
     *         exists.
     */
    @Override
    public double getMaxValue(TagReadable tagable, boolean pixelPadding) {
        // Computes min and max as slope can be negative
        return Math.max(pixel2mLUT(super.getMinValue(tagable, pixelPadding), tagable, pixelPadding),
            pixel2mLUT(super.getMaxValue(tagable, pixelPadding), tagable, pixelPadding));
    }

    @Override
    protected boolean isGrayImage(RenderedImage source) {
        Boolean val = (Boolean) getTagValue(TagW.MonoChrome);
        return val == null ? true : val;
    }

    /**
     * Data representation of the pixel samples. Each sample shall have the same pixel representation. Enumerated
     * Values: 0000H = unsigned integer. 0001H = 2's complement
     *
     * @return true if Tag exist and if explicitly defined a signed
     * @see DICOM standard PS 3.3 - §C.7.6.3 - Image Pixel Module
     */

    public boolean isPixelRepresentationSigned() {
        Integer pixelRepresentation = TagD.getTagValue(this, Tag.PixelRepresentation, Integer.class);
        return (pixelRepresentation != null) && (pixelRepresentation != 0);
    }

    public boolean isPhotometricInterpretationInverse(TagReadable tagable) {
        String prLUTShape = TagD.getTagValue(tagable, Tag.PresentationLUTShape, String.class);
        if (prLUTShape == null) {
            prLUTShape = TagD.getTagValue(this, Tag.PresentationLUTShape, String.class);
        }
        return prLUTShape != null ? "INVERSE".equals(prLUTShape) : "MONOCHROME1" //$NON-NLS-1$ //$NON-NLS-2$
            .equalsIgnoreCase(getPhotometricInterpretation());
    }

    /**
     * In the case where Rescale Slope and Rescale Intercept are used for modality pixel transformation, the output
     * ranges may be signed even if Pixel Representation is unsigned.
     *
     * @param pixelPadding
     *
     * @return
     */
    public boolean isModalityLutOutSigned(TagReadable tagable, boolean pixelPadding) {
        boolean signed = isPixelRepresentationSigned();
        return getMinValue(tagable, pixelPadding) < 0 ? true : signed;
    }

    public int getBitsStored() {
        return TagD.getTagValue(this, Tag.BitsStored, Integer.class);
    }

    public int getBitsAllocated() {
        return TagD.getTagValue(this, Tag.BitsAllocated, Integer.class);
    }

    @Override
    public String toString() {
        return TagD.getTagValue(this, Tag.SOPInstanceUID, String.class);
    }

    public double getRescaleIntercept(TagReadable tagable) {
        Double prIntercept = TagD.getTagValue(tagable, Tag.RescaleIntercept, Double.class);
        Double intercept =
            prIntercept == null ? TagD.getTagValue(this, Tag.RescaleIntercept, Double.class) : prIntercept;
        return intercept == null ? 0.0 : intercept;
    }

    public double getRescaleSlope(TagReadable tagable) {
        Double prSlope = TagD.getTagValue(tagable, Tag.RescaleSlope, Double.class);
        Double slope = prSlope == null ? TagD.getTagValue(this, Tag.RescaleSlope, Double.class) : prSlope;
        return slope == null ? 1.0 : slope;
    }

    public double pixel2mLUT(Number pixelValue, TagReadable tagable, boolean pixelPadding) {
        if (pixelValue != null) {
            LookupTableJAI lookup = getModalityLookup(tagable, pixelPadding);
            if (lookup != null) {
                int val = pixelValue.intValue();
                if (val >= lookup.getOffset() && val < lookup.getOffset() + lookup.getNumEntries()) {
                    return lookup.lookup(0, val);
                }
            }
            return pixelValue.doubleValue();
        }
        return 0;
    }

    public int getMinAllocatedValue(TagReadable tagable, boolean pixelPadding) {
        boolean signed = isModalityLutOutSigned(tagable, pixelPadding);
        int bitsAllocated = getBitsAllocated();
        int maxValue = signed ? (1 << (bitsAllocated - 1)) - 1 : ((1 << bitsAllocated) - 1);
        return signed ? -(maxValue + 1) : 0;
    }

    public int getMaxAllocatedValue(TagReadable tagable, boolean pixelPadding) {
        boolean signed = isModalityLutOutSigned(tagable, pixelPadding);
        int bitsAllocated = getBitsAllocated();
        return signed ? (1 << (bitsAllocated - 1)) - 1 : ((1 << bitsAllocated) - 1);
    }

    public int getAllocatedOutRangeSize() {
        int bitsAllocated = getBitsAllocated();
        return (1 << bitsAllocated) - 1;
    }

    /**
     * The value of Photometric Interpretation specifies the intended interpretation of the image pixel data.
     *
     * @return following values (MONOCHROME1 , MONOCHROME2 , PALETTE COLOR ....) Other values are permitted but the
     *         meaning is not defined by this Standard.
     */
    public String getPhotometricInterpretation() {
        return TagD.getTagValue(this, Tag.PhotometricInterpretation, String.class);
    }

    public boolean isPhotometricInterpretationMonochrome() {
        String photometricInterpretation = getPhotometricInterpretation();

        return photometricInterpretation != null && //
            ("MONOCHROME1".equalsIgnoreCase(photometricInterpretation) //$NON-NLS-1$
                || "MONOCHROME2" //$NON-NLS-1$
                    .equalsIgnoreCase(photometricInterpretation));
    }

    /**
     *
     * Pixel Padding Value is used to pad grayscale images (those with a Photometric Interpretation of MONOCHROME1 or
     * MONOCHROME2)<br>
     * Pixel Padding Value specifies either a single value of this padding value, or when combined with Pixel Padding
     * Range Limit, a range of values (inclusive) that are padding.<br>
     * <br>
     * <b>Note :</b> It is explicitly described in order to prevent display applications from taking it into account
     * when determining the dynamic range of an image, since the Pixel Padding Value will be outside the range between
     * the minimum and maximum values of the pixels in the native image
     *
     * @see DICOM standard PS 3.3 - §C.7.5.1.1.2 - Pixel Padding Value and Pixel Padding Range Limit
     */

    public Integer getPaddingValue() {
        return TagD.getTagValue(this, Tag.PixelPaddingValue, Integer.class);
    }

    /**
     * @see getPaddingValue()
     */
    public Integer getPaddingLimit() {
        return TagD.getTagValue(this, Tag.PixelPaddingRangeLimit, Integer.class);

    }

    public LutParameters getLutParameters(TagReadable tagable, boolean pixelPadding, LookupTableJAI mLUTSeq,
        boolean inversePaddingMLUT) {
        Integer paddingValue = getPaddingValue();

        boolean isSigned = isPixelRepresentationSigned();
        int bitsStored = getBitsStored();
        double intercept = getRescaleIntercept(tagable);
        double slope = getRescaleSlope(tagable);

        // No need to have a modality lookup table
        if (bitsStored > 16
            || (MathUtil.isEqual(slope, 1.0) && MathUtil.isEqualToZero(intercept) && paddingValue == null)) {
            return null;
        }

        Integer paddingLimit = getPaddingLimit();
        boolean outputSigned = false;
        int bitsOutputLut;
        if (mLUTSeq == null) {
            double minValue = super.getMinValue(tagable, pixelPadding) * slope + intercept;
            double maxValue = super.getMaxValue(tagable, pixelPadding) * slope + intercept;
            bitsOutputLut = Integer.SIZE - Integer.numberOfLeadingZeros((int) Math.round(maxValue - minValue));
            outputSigned = minValue < 0 ? true : isSigned;
            if (outputSigned && bitsOutputLut <= 8) {
                // Allows to handle negative values with 8-bit image
                bitsOutputLut = 9;
            }
        } else {
            bitsOutputLut = mLUTSeq.getDataType() == DataBuffer.TYPE_BYTE ? 8 : 16;
        }
        return new LutParameters(intercept, slope, pixelPadding, paddingValue, paddingLimit, bitsStored, isSigned,
            outputSigned, bitsOutputLut, inversePaddingMLUT);

    }

    public LookupTableJAI getModalityLookup(TagReadable tagable, boolean pixelPadding) {
        return getModalityLookup(tagable, pixelPadding, false);
    }

    /**
     * DICOM PS 3.3 $C.11.1 Modality LUT Module
     *
     * The LUT Data contains the LUT entry values.
     *
     * The output range of the Modality LUT Module depends on whether or not Rescale Slope (0028,1053) and Rescale
     * Intercept (0028,1052) or the Modality LUT Sequence (0028,3000) are used. In the case where Rescale Slope and
     * Rescale Intercept are used, the output ranges from (minimum pixel value*Rescale Slope+Rescale Intercept) to
     * (maximum pixel value*Rescale - Slope+Rescale Intercept), where the minimum and maximum pixel values are
     * determined by Bits Stored and Pixel Representation. Note: This range may be signed even if Pixel Representation
     * is unsigned.
     *
     * In the case where the Modality LUT Sequence is used, the output range is from 0 to 2n-1 where n is the third
     * value of LUT Descriptor. This range is always unsigned.
     *
     * @param pixelPadding
     * @param inverseLUT
     * @return the modality lookup table
     */
    protected LookupTableJAI getModalityLookup(TagReadable tagable, boolean pixelPadding, boolean inverseLUTAction) {
        Integer paddingValue = getPaddingValue();
        LookupTableJAI prModLut = (LookupTableJAI) (tagable != null ? tagable.getTagValue(TagW.ModalityLUTData) : null);
        final LookupTableJAI mLUTSeq = prModLut == null ? (LookupTableJAI) getTagValue(TagW.ModalityLUTData) : prModLut;
        if (mLUTSeq != null) {
            if (!pixelPadding || paddingValue == null) {
                if (super.getMinValue(tagable, false) >= mLUTSeq.getOffset()
                    && super.getMaxValue(tagable, false) < mLUTSeq.getOffset() + mLUTSeq.getNumEntries()) {
                    return mLUTSeq;
                } else if (prModLut == null) {
                    // Remove MLut as it cannot be used.
                    tags.remove(TagW.ModalityLUTData);
                    LOGGER.warn(
                        "Pixel values doesn't match to Modality LUT sequence table. So the Modality LUT is not applied."); //$NON-NLS-1$
                }
            } else {
                LOGGER.warn("Cannot apply Modality LUT sequence and Pixel Padding"); //$NON-NLS-1$
            }
        }

        boolean inverseLut = isPhotometricInterpretationInverse(tagable);
        if (pixelPadding) {
            inverseLut ^= inverseLUTAction;
        }
        LutParameters lutparams = getLutParameters(tagable, pixelPadding, mLUTSeq, inverseLut);
        // Not required to have a modality lookup table
        if (lutparams == null) {
            return null;
        }
        LookupTableJAI modalityLookup = LUT_Cache.get(lutparams);

        if (modalityLookup != null) {
            return modalityLookup;
        }

        if (mLUTSeq != null) {
            if (mLUTSeq.getNumBands() == 1) {
                if (mLUTSeq.getDataType() == DataBuffer.TYPE_BYTE) {
                    byte[] data = mLUTSeq.getByteData(0);
                    if (data != null) {
                        modalityLookup = new LookupTableJAI(data, mLUTSeq.getOffset(0));
                    }
                } else {
                    short[] data = mLUTSeq.getShortData(0);
                    if (data != null) {
                        modalityLookup = new LookupTableJAI(data, mLUTSeq.getOffset(0),
                            mLUTSeq.getData() instanceof DataBufferUShort);
                    }
                }
            }
            if (modalityLookup == null) {
                modalityLookup = mLUTSeq;
            }
        } else {
            modalityLookup = DicomImageUtils.createRescaleRampLut(lutparams);
        }

        if (isPhotometricInterpretationMonochrome()) {
            DicomImageUtils.applyPixelPaddingToModalityLUT(modalityLookup, lutparams);
        }
        LUT_Cache.put(lutparams, modalityLookup);
        return modalityLookup;
    }

    /**
     *
     * @param window
     * @param level
     * @param shape
     * @param fillLutOutside
     * @param pixelPadding
     *
     * @return 8 bits unsigned Lookup Table
     */
    public LookupTableJAI getVOILookup(TagReadable tagable, Double window, Double level, Double minLevel,
        Double maxLevel, LutShape shape, boolean fillLutOutside, boolean pixelPadding) {

        if (window == null || level == null || shape == null || minLevel == null || maxLevel == null) {
            return null;
        }

        int minValue;
        int maxValue;
        /*
         * When pixel padding is activated, VOI LUT must extend to the min bit stored value when MONOCHROME2 and to the
         * max bit stored value when MONOCHROME1. See C.7.5.1.1.2
         */
        if (fillLutOutside || (getPaddingValue() != null && isPhotometricInterpretationMonochrome())) {
            minValue = getMinAllocatedValue(tagable, pixelPadding);
            maxValue = getMaxAllocatedValue(tagable, pixelPadding);
        } else {
            minValue = minLevel.intValue();
            maxValue = maxLevel.intValue();
        }

        return DicomImageUtils.createWindowLevelLut(shape, window, level, minValue, maxValue, 8, false,
            isPhotometricInterpretationInverse(tagable));
    }

    /**
     * @return default as first element of preset List <br>
     *         Note : null should never be returned since auto is at least one preset
     */
    public PresetWindowLevel getDefaultPreset(boolean pixelPadding) {
        List<PresetWindowLevel> presetList = getPresetList(pixelPadding);
        return (presetList != null && !presetList.isEmpty()) ? presetList.get(0) : null;
    }

    public List<PresetWindowLevel> getPresetList(boolean pixelPadding) {
        if (windowingPresetCollection == null && isImageAvailable()) {
            String type = Messages.getString("PresetWindowLevel.dcm_preset"); //$NON-NLS-1$
            windowingPresetCollection = PresetWindowLevel.getPresetCollection(this, this, pixelPadding, type);
        }
        return windowingPresetCollection;
    }

    public boolean containsPreset(PresetWindowLevel preset) {
        if (preset != null) {
            List<PresetWindowLevel> collection = getPresetList(false);
            if (collection != null) {
                return collection.contains(preset);
            }
        }
        return false;
    }

    public Collection<LutShape> getLutShapeCollection(boolean pixelPadding) {
        if (lutShapeCollection != null) {
            return lutShapeCollection;
        }

        lutShapeCollection = new LinkedHashSet<>();
        List<PresetWindowLevel> presetList = getPresetList(pixelPadding);
        if (presetList != null) {
            for (PresetWindowLevel preset : presetList) {
                lutShapeCollection.add(preset.getLutShape());
            }
        }
        lutShapeCollection.addAll(LutShape.DEFAULT_FACTORY_FUNCTIONS);

        return lutShapeCollection;
    }

    /**
     *
     * @param imageSource
     * @param pixelPadding
     * @return Histogram of the image source after modality lookup rescaled
     */

    public Histogram getHistogram(RenderedImage imageSource, TagReadable tagable, boolean pixelPadding) {
        LookupTableJAI lookup = getModalityLookup(tagable, pixelPadding);
        if (imageSource == null || lookup == null) {
            return null;
        }
        // TODO instead of computing histo from image get Dicom attribute if present. Handle pixel padding!

        ParameterBlock pb = new ParameterBlock();
        pb.addSource(imageSource);
        pb.add(lookup);
        final RenderedImage imageModalityTransformed = JAI.create("lookup", pb, null); //$NON-NLS-1$

        pb.removeSources();
        pb.removeParameters();

        pb.addSource(imageModalityTransformed);
        pb.add(null); // No ROI
        pb.add(1); // Sampling
        pb.add(1); // periods
        pb.add(new int[] { getAllocatedOutRangeSize() }); // Num. bins.
        pb.add(new double[] { getMinAllocatedValue(tagable, pixelPadding) }); // Min value to be considered.
        pb.add(new double[] { getMaxAllocatedValue(tagable, pixelPadding) }); // Max value to be considered.

        RenderedOp op = JAI.create("histogram", pb, ImageToolkit.NOCACHE_HINT); //$NON-NLS-1$
        return (Histogram) op.getProperty("histogram"); //$NON-NLS-1$
    }

    @Override
    protected void findMinMaxValues(RenderedImage img, boolean exclude8bitImage) {
        /*
         * This function can be called several times from the inner class Load. min and max will be computed only once.
         */

        if (img != null && !isImageAvailable()) {
            // Cannot trust SmallestImagePixelValue and LargestImagePixelValue values! So search min and max values
            int bitsStored = getBitsStored();
            int bitsAllocated = getBitsAllocated();

            minPixelValue = null;
            maxPixelValue = null;

            boolean monochrome = isPhotometricInterpretationMonochrome();
            if (monochrome) {
                Integer paddingValue = getPaddingValue();
                if (paddingValue != null) {
                    Integer paddingLimit = getPaddingLimit();
                    int paddingValueMin = (paddingLimit == null) ? paddingValue : Math.min(paddingValue, paddingLimit);
                    int paddingValueMax = (paddingLimit == null) ? paddingValue : Math.max(paddingValue, paddingLimit);
                    findMinMaxValues(img, paddingValueMin, paddingValueMax);
                }
            }

            if (!isImageAvailable()) {
                super.findMinMaxValues(img, !monochrome);
            }

            if (bitsStored < bitsAllocated && isImageAvailable()) {
                boolean isSigned = isPixelRepresentationSigned();
                int minInValue = isSigned ? -(1 << (bitsStored - 1)) : 0;
                int maxInValue = isSigned ? (1 << (bitsStored - 1)) - 1 : (1 << bitsStored) - 1;
                if (minPixelValue < minInValue || maxPixelValue > maxInValue) {
                    /*
                     *
                     *
                     * When the image contains values outside the bits stored values, the bits stored is replaced by the
                     * bits allocated for having a LUT which handles all the values.
                     *
                     * Overlays in pixel data should be masked before finding min and max.
                     */
                    setTag(TagD.get(Tag.BitsStored), bitsAllocated);
                }
            }
            /*
             * Lazily compute image pixel transformation here since inner class Load is called from a separate and
             * dedicated worker Thread. Also, it will be computed only once
             *
             * Considering that the default pixel padding option is true and Inverse LUT action is false
             */
            getModalityLookup(null, true);
        }
    }

    /**
     * Computes Min/Max values from Image excluding range of values provided
     *
     * @param img
     * @param paddingValueMin
     * @param paddingValueMax
     */
    private void findMinMaxValues(RenderedImage img, double paddingValueMin, double paddingValueMax) {
        if (img != null) {
            int datatype = img.getSampleModel().getDataType();
            if (datatype == DataBuffer.TYPE_BYTE) {
                this.minPixelValue = 0.0;
                this.maxPixelValue = 255.0;
            } else {
                RenderedOp dst =
                    ImageStatisticsDescriptor.create(img, (ROI) null, 1, 1, paddingValueMin, paddingValueMax, null);
                // To ensure this image won't be stored in tile cache
                ((OpImage) dst.getRendering()).setTileCache(null);

                double[][] extrema = (double[][]) dst.getProperty("statistics"); //$NON-NLS-1$
                double min = Double.MAX_VALUE;
                double max = -Double.MAX_VALUE;
                int numBands = dst.getSampleModel().getNumBands();

                for (int i = 0; i < numBands; i++) {
                    min = Math.min(min, extrema[0][i]);
                    max = Math.max(max, extrema[1][i]);
                }
                this.minPixelValue = min;
                this.maxPixelValue = max;
                // Handle special case when min and max are equal, ex. black image
                // + 1 to max enables to display the correct value
                if (this.minPixelValue.equals(this.maxPixelValue)) {
                    this.maxPixelValue += 1.0;
                }
            }
        }
    }

    public double[] getDisplayPixelSize() {
        return new double[] { pixelSizeX, pixelSizeY };
    }

    public double getFullDynamicWidth(TagReadable tagable, boolean pixelPadding) {
        return getMaxValue(tagable, pixelPadding) - getMinValue(tagable, pixelPadding);
    }

    public double getFullDynamicCenter(TagReadable tagable, boolean pixelPadding) {
        double minValue = getMinValue(tagable, pixelPadding);
        double maxValue = getMaxValue(tagable, pixelPadding);
        return minValue + (maxValue - minValue) / 2.f;
    }

    @Override
    public LutShape getDefaultShape(boolean pixelPadding) {
        PresetWindowLevel defaultPreset = getDefaultPreset(pixelPadding);
        return (defaultPreset != null) ? defaultPreset.getLutShape() : super.getDefaultShape(pixelPadding);
    }

    @Override
    public double getDefaultWindow(boolean pixelPadding) {
        PresetWindowLevel defaultPreset = getDefaultPreset(pixelPadding);
        return (defaultPreset != null) ? defaultPreset.getWindow() : super.getDefaultWindow(pixelPadding);
    }

    @Override
    public double getDefaultLevel(boolean pixelPadding) {
        PresetWindowLevel defaultPreset = getDefaultPreset(pixelPadding);
        return (defaultPreset != null) ? defaultPreset.getLevel() : super.getDefaultLevel(pixelPadding);

    }

    /**
     * @param imageSource
     *            is the RenderedImage upon which transformation is done
     * @param window
     *            is width from low to high input values around level. If null, getDefaultWindow() value is used
     * @param level
     *            is center of window values. If null, getDefaultLevel() value is used
     * @param lutShape
     *            defines the shape of applied lookup table transformation. If null getDefaultLutShape() is used
     * @param pixPadding
     *            indicates if some padding values defined in ImageElement should be applied or not. If null, TRUE is
     *            considered
     * @return
     */
    @Override
    public RenderedImage getRenderedImage(final RenderedImage imageSource, Map<String, Object> params) {
        if (imageSource == null) {
            return null;
        }

        SampleModel sampleModel = imageSource.getSampleModel();
        if (sampleModel == null) {
            return null;
        }

        Double window = null;
        Double level = null;
        Double levelMin = null;
        Double levelMax = null;
        LutShape lutShape = null;
        Boolean pixelPadding = null;
        Boolean inverseLUT = null;
        Boolean fillLutOutside = null;
        Boolean wlOnColorImage = null;
        LookupTableJAI prLutData = null;
        TagReadable prTags = null;

        if (params != null) {
            window = (Double) params.get(ActionW.WINDOW.cmd());
            level = (Double) params.get(ActionW.LEVEL.cmd());
            levelMin = (Double) params.get(ActionW.LEVEL_MIN.cmd());
            levelMax = (Double) params.get(ActionW.LEVEL_MAX.cmd());
            lutShape = (LutShape) params.get(ActionW.LUT_SHAPE.cmd());
            pixelPadding = (Boolean) params.get(ActionW.IMAGE_PIX_PADDING.cmd());
            inverseLUT = (Boolean) params.get(PseudoColorOp.P_LUT_INVERSE);
            fillLutOutside = (Boolean) params.get(WindowOp.P_FILL_OUTSIDE_LUT);
            wlOnColorImage = (Boolean) params.get(WindowOp.P_APPLY_WL_COLOR);
            prTags = (TagReadable) params.get(WindowAndPresetsOp.P_PR_ELEMENT);
            if (prTags != null) {
                prLutData = (LookupTableJAI) prTags.getTagValue(TagW.PRLUTsData);
            }

        }

        boolean pixPadding = LangUtil.getNULLtoTrue(pixelPadding);
        boolean invLUT = LangUtil.getNULLtoFalse(inverseLUT);
        double windowValue = (window == null) ? getDefaultWindow(pixPadding) : window;
        double levelValue = (level == null) ? getDefaultLevel(pixPadding) : level;
        LutShape lut = (lutShape == null) ? getDefaultShape(pixPadding) : lutShape;
        double minLevel;
        double maxLevel;
        if (levelMin == null || levelMax == null) {
            minLevel = Math.min(levelValue - windowValue / 2.0, getMinValue(prTags, pixPadding));
            maxLevel = Math.max(levelValue + windowValue / 2.0, getMaxValue(prTags, pixPadding));
        } else {
            minLevel = Math.min(levelMin, getMinValue(prTags, pixPadding));
            maxLevel = Math.max(levelMax, getMaxValue(prTags, pixPadding));
        }

        int datatype = sampleModel.getDataType();

        if (datatype >= DataBuffer.TYPE_BYTE && datatype < DataBuffer.TYPE_INT) {
            LookupTableJAI modalityLookup = getModalityLookup(prTags, pixPadding, invLUT);

            // RenderingHints hints = new RenderingHints(JAI.KEY_IMAGE_LAYOUT, new ImageLayout(imageSource));
            RenderedImage imageModalityTransformed =
                modalityLookup == null ? imageSource : LookupDescriptor.create(imageSource, modalityLookup, null);

            /*
             * C.11.2.1.2 Window center and window width
             *
             * Theses Attributes shall be used only for Images with Photometric Interpretation (0028,0004) values of
             * MONOCHROME1 and MONOCHROME2. They have no meaning for other Images.
             */
            if (!LangUtil.getNULLtoFalse(wlOnColorImage) && !isPhotometricInterpretationMonochrome()) {
                /*
                 * If photometric interpretation is not monochrome do not apply VOILUT. It is necessary for
                 * PALETTE_COLOR.
                 */
                return imageModalityTransformed;
            }

            LookupTableJAI voiLookup = null;
            if (prLutData == null || lut.getLookup() != null) {
                voiLookup = getVOILookup(prTags, windowValue, levelValue, minLevel, maxLevel, lut,
                    LangUtil.getNULLtoFalse(fillLutOutside), pixPadding);
            }

            if (prLutData == null) {
                // BUG fix: for some images the color model is null. Creating 8 bits gray model layout fixes this issue.
                return LookupDescriptor.create(imageModalityTransformed, voiLookup,
                    LayoutUtil.createGrayRenderedImage());
            }

            RenderedImage imageVoiTransformed = voiLookup == null ? imageModalityTransformed
                : LookupDescriptor.create(imageModalityTransformed, voiLookup, null);
            // BUG fix: for some images the color model is null. Creating 8 bits gray model layout fixes this issue.
            return LookupDescriptor.create(imageVoiTransformed, prLutData, LayoutUtil.createGrayRenderedImage());

        } else if (datatype == DataBuffer.TYPE_INT || datatype == DataBuffer.TYPE_FLOAT
            || datatype == DataBuffer.TYPE_DOUBLE) {
            double low = levelValue - windowValue / 2.0;
            double high = levelValue + windowValue / 2.0;
            double range = high - low;
            if (range < 1.0 && datatype == DataBuffer.TYPE_INT) {
                range = 1.0;
            }
            double slope = 255.0 / range;
            double yint = 255.0 - slope * high;

            RenderedOp rescale =
                RescaleDescriptor.create(imageSource, new double[] { slope }, new double[] { yint }, null);
            return FormatDescriptor.create(rescale, DataBuffer.TYPE_BYTE, null);
        }
        return null;
    }

    public GeometryOfSlice getDispSliceGeometry() {
        // The geometry is adapted to get square pixel as all the images are displayed with square pixel.
        double[] imgOr = TagD.getTagValue(this, Tag.ImageOrientationPatient, double[].class);
        if (imgOr != null && imgOr.length == 6) {
            double[] pos = TagD.getTagValue(this, Tag.ImagePositionPatient, double[].class);
            if (pos != null && pos.length == 3) {
                Double sliceTickness = TagD.getTagValue(this, Tag.SliceThickness, Double.class);
                if (sliceTickness == null) {
                    sliceTickness = getPixelSize();
                }
                double[] spacing = { getPixelSize(), getPixelSize(), sliceTickness };
                Integer rows = TagD.getTagValue(this, Tag.Rows, Integer.class);
                Integer columns = TagD.getTagValue(this, Tag.Columns, Integer.class);
                if (rows != null && columns != null && rows > 0 && columns > 0) {
                    // SliceTickness is only use in IntersectVolume
                    // Multiply rows and columns by getZoomScale() to have square pixel image size
                    return new GeometryOfSlice(new double[] { imgOr[0], imgOr[1], imgOr[2] },
                        new double[] { imgOr[3], imgOr[4], imgOr[5] }, pos, spacing, sliceTickness,
                        new double[] { rows * getRescaleY(), columns * getRescaleX(), 1 });
                }
            }
        }
        return null;
    }

    public GeometryOfSlice getSliceGeometry() {
        double[] imgOr = TagD.getTagValue(this, Tag.ImageOrientationPatient, double[].class);
        if (imgOr != null && imgOr.length == 6) {
            double[] pos = TagD.getTagValue(this, Tag.ImagePositionPatient, double[].class);
            if (pos != null && pos.length == 3) {
                Double sliceTickness = TagD.getTagValue(this, Tag.SliceThickness, Double.class);
                if (sliceTickness == null) {
                    sliceTickness = getPixelSize();
                }
                double[] pixSize = getDisplayPixelSize();
                double[] spacing = { pixSize[0], pixSize[1], sliceTickness };
                Integer rows = TagD.getTagValue(this, Tag.Rows, Integer.class);
                Integer columns = TagD.getTagValue(this, Tag.Columns, Integer.class);
                if (rows != null && columns != null && rows > 0 && columns > 0) {
                    return new GeometryOfSlice(new double[] { imgOr[0], imgOr[1], imgOr[2] },
                        new double[] { imgOr[3], imgOr[4], imgOr[5] }, pos, spacing, sliceTickness,
                        new double[] { rows, columns, 1 });
                }
            }
        }
        return null;
    }

}
