package org.broadinstitute.sting.gatk.walkers.annotator;

import org.broadinstitute.sting.utils.Pair;
import org.broadinstitute.sting.utils.pileup.ReadBackedPileup;
import org.broadinstitute.sting.utils.BaseUtils;
import org.broadinstitute.sting.utils.pileup.PileupElement;
import org.broadinstitute.sting.utils.genotype.vcf.VCFInfoHeaderLine;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.contexts.StratifiedAlignmentContext;
import org.broadinstitute.sting.gatk.contexts.variantcontext.VariantContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.walkers.annotator.interfaces.*;

import java.util.Map;
import java.util.HashMap;


public class SecondBaseSkew implements InfoFieldAnnotation, ExperimentalAnnotation {
    private final static double epsilon = Math.pow(10.0,-12.0);
    private final static String KEY_NAME = "2b_Chi";
    private final static double[] UNIFORM_ON_OFF_RATIO = {1.0/3.0, 2.0/3.0};
    private double[] proportionExpectations = UNIFORM_ON_OFF_RATIO;

    public String getKeyName() { return KEY_NAME; }

    public VCFInfoHeaderLine getDescription() { return new VCFInfoHeaderLine(KEY_NAME, 1, VCFInfoHeaderLine.INFO_TYPE.Float, "Chi-square Secondary Base Skew"); }

    public Map<String, Object> annotate(RefMetaDataTracker tracker, ReferenceContext ref, Map<String, StratifiedAlignmentContext> stratifiedContexts, VariantContext vc) {
        String annotation = getAnnotation(ref, stratifiedContexts, vc);
        if ( annotation == null )
            return null;
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(getKeyName(), annotation);            
        return map;
    }

    public String getAnnotation(ReferenceContext ref, Map<String, StratifiedAlignmentContext> stratifiedContexts, VariantContext vc) {
        if ( !vc.isBiallelic() || !vc.isSNP() )
            return null;

        char alternate = vc.getAlternateAllele(0).toString().charAt(0);

        Pair<Integer, Integer> depth = new Pair<Integer, Integer>(0, 0);
        for ( String sample : stratifiedContexts.keySet() ) {
            //Pair<Integer,Integer> sampleDepth = getSecondaryPileupNonrefCount(ref.getBase(),stratifiedContexts.get(sample).getContext(StratifiedAlignmentContext.StratifiedContextType.COMPLETE).getPileup(), alternate);
            Pair<Integer, Integer> sampleDepth = getSecondaryPileupNonrefCount(ref.getBase(), stratifiedContexts.get(sample).getContext(StratifiedAlignmentContext.StratifiedContextType.COMPLETE).getBasePileup(), alternate);
            depth.first += sampleDepth.first;
            depth.second += sampleDepth.second;
        }

        if ( depth.first == 0 )
            return null;

        double biasedProportion = (1.0 + depth.second) / (1.0 + depth.first);
        double p_transformed = transform(biasedProportion, depth.first+1);
        double expected_transformed = transform(proportionExpectations[0], depth.first+1);
        double chi_square =  Math.signum(biasedProportion - proportionExpectations[0])*Math.min(Math.pow(p_transformed - expected_transformed, 2), Double.MAX_VALUE);
        return String.format("%f", chi_square);
    }

    private double transform( double proportion, int depth ) {
        proportion = proportion - epsilon;
        return proportion / ( Math.sqrt ( proportion*(1-proportion)/depth ) );
    }

    private Pair<Integer, Integer> getSecondaryPileupNonrefCount(char ref, ReadBackedPileup p, char snp ) {
        int variantDepth = 0;
        int variantsWithRefSecondBase = 0;

        for (PileupElement pile : p ) {
            byte pbase = pile.getBase();
            byte sbase = pile.getSecondBase();

            if ( BaseUtils.isRegularBase((char)sbase) && BaseUtils.basesAreEqual(pbase, (byte) snp) ) {
                variantDepth++;
                if ( BaseUtils.basesAreEqual(sbase, (byte)ref) ) {
                    variantsWithRefSecondBase++;
                }
            }
        }

        return new Pair<Integer, Integer>(variantDepth, variantsWithRefSecondBase);
    }
}
