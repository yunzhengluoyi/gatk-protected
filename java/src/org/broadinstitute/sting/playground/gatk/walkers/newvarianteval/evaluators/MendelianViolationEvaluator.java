package org.broadinstitute.sting.playground.gatk.walkers.newvarianteval.evaluators;

import org.broad.tribble.util.variantcontext.Allele;
import org.broad.tribble.util.variantcontext.Genotype;
import org.broad.tribble.util.variantcontext.VariantContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.playground.gatk.walkers.newvarianteval.NewVariantEvalWalker;
import org.broadinstitute.sting.playground.gatk.walkers.newvarianteval.tags.Analysis;
import org.broadinstitute.sting.playground.gatk.walkers.newvarianteval.tags.DataPoint;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;

import java.util.List;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Mendelian violation detection and counting
 * <p/>
 * a violation looks like:
 * Suppose dad = A/B and mom = C/D
 * The child can be [A or B] / [C or D].
 * If the child doesn't match this, the site is a violation
 * <p/>
 * Some examples:
 * <p/>
 * mom = A/A, dad = C/C
 * child can be A/C only
 * <p/>
 * mom = A/C, dad = C/C
 * child can be A/C or C/C
 * <p/>
 * mom = A/C, dad = A/C
 * child can be A/A, A/C, C/C
 * <p/>
 * The easiest way to do this calculation is to:
 * <p/>
 * Get alleles for mom => A/B
 * Get alleles for dad => C/D
 * Make allowed genotypes for child: A/C, A/D, B/C, B/D
 * Check that the child is one of these.
 */
@Analysis(name = "Mendelian Violation Evaluator", description = "Mendelian Violation Evaluator")
public class MendelianViolationEvaluator extends VariantEvaluator {

    @DataPoint(description = "Number of mendelian variants found")
    long nVariants;

    @DataPoint(description = "Number of mendelian violations found")
    long nViolations;

    @DataPoint(description = "number of child hom ref calls where the parent was hom variant")
    long KidHomRef_ParentHomVar;
    @DataPoint(description = "number of child het calls where the parent was hom ref")
    long KidHet_ParentsHomRef;
    @DataPoint(description = "number of child het calls where the parent was hom variant")
    long KidHet_ParentsHomVar;
    @DataPoint(description = "number of child hom variant calls where the parent was hom ref")
    long KidHomVar_ParentHomRef;

    TrioStructure trio;
    double mendelianViolationQualThreshold;

    private static Pattern FAMILY_PATTERN = Pattern.compile("(.*)\\+(.*)=(.*)");

    public static class TrioStructure {
        public String mom, dad, child;
    }

    public static TrioStructure parseTrioDescription(String family) {
        Matcher m = FAMILY_PATTERN.matcher(family);
        if (m.matches()) {
            TrioStructure trio = new TrioStructure();
            //System.out.printf("Found a family pattern: %s%n", parent.FAMILY_STRUCTURE);
            trio.mom = m.group(1);
            trio.dad = m.group(2);
            trio.child = m.group(3);
            return trio;
        } else {
            throw new IllegalArgumentException("Malformatted family structure string: " + family + " required format is mom+dad=child");
        }
    }

    // todo: fix
    public void initialize(NewVariantEvalWalker walker) {
        trio = parseTrioDescription(walker.getFamilyStructure());
        mendelianViolationQualThreshold = walker.getMendelianViolationQualThreshold();
    }


    public boolean enabled() {
        //return getVEWalker().FAMILY_STRUCTURE != null;
        return true;
    }

    private double getQThreshold() {
        //return getVEWalker().MENDELIAN_VIOLATION_QUAL_THRESHOLD / 10;  // we aren't 10x scaled in the GATK a la phred
        return mendelianViolationQualThreshold / 10;  // we aren't 10x scaled in the GATK a la phred
        //return 0.0;
    }

    public String getName() {
        return "mendelian_violations";
    }

    public int getComparisonOrder() {
        return 1;   // we only need to see each eval track
    }

    public String update1(VariantContext vc, RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context) {
        if (vc.isBiallelic() && vc.hasGenotypes()) { // todo -- currently limited to biallelic loci
            Genotype momG = vc.getGenotype(trio.mom);
            Genotype dadG = vc.getGenotype(trio.dad);
            Genotype childG = vc.getGenotype(trio.child);

            if (includeGenotype(momG) && includeGenotype(dadG) && includeGenotype(childG)) {
                nVariants++;

                if (momG == null || dadG == null || childG == null)
                    throw new IllegalArgumentException(String.format("VariantContext didn't contain genotypes for expected trio members: mom=%s dad=%s child=%s", trio.mom, trio.dad, trio.child));

                // all genotypes are good, so let's see if child is a violation

                if (isViolation(vc, momG, dadG, childG)) {
                    nViolations++;

                    String label;
                    if (childG.isHomRef() && (momG.isHomVar() || dadG.isHomVar())) {
                        label = "KidHomRef_ParentHomVar";
                        KidHomRef_ParentHomVar++;
                    } else if (childG.isHet() && (momG.isHomRef() && dadG.isHomRef())) {
                        label = "KidHet_ParentsHomRef";
                        KidHet_ParentsHomRef++;
                    } else if (childG.isHet() && (momG.isHomVar() && dadG.isHomVar())) {
                        label = "KidHet_ParentsHomVar";
                        KidHet_ParentsHomVar++;
                    } else if (childG.isHomVar() && (momG.isHomRef() || dadG.isHomRef())) {
                        label = "KidHomVar_ParentHomRef";
                        KidHomVar_ParentHomRef++;
                    } else {
                        throw new ReviewedStingException("BUG: unexpected child genotype class " + childG);
                    }

                    return "MendelViolation=" + label;
                }
            }
        }

        return null; // we don't capture any intersting sites
    }

    private boolean includeGenotype(Genotype g) {
        return g.getNegLog10PError() > getQThreshold() && g.isCalled();
    }

    public static boolean isViolation(VariantContext vc, Genotype momG, Genotype dadG, Genotype childG) {
        return isViolation(vc, momG.getAlleles(), dadG.getAlleles(), childG.getAlleles());
    }

    public static boolean isViolation(VariantContext vc, TrioStructure trio ) {
        return isViolation(vc, vc.getGenotype(trio.mom), vc.getGenotype(trio.dad), vc.getGenotype(trio.child) );
    }

    public static boolean isViolation(VariantContext vc, List<Allele> momA, List<Allele> dadA, List<Allele> childA) {
        //VariantContext momVC = vc.subContextFromGenotypes(momG);
        //VariantContext dadVC = vc.subContextFromGenotypes(dadG);
        int i = 0;
        Genotype childG = new Genotype("kidG", childA);
        for (Allele momAllele : momA) {
            for (Allele dadAllele : dadA) {
                if (momAllele.isCalled() && dadAllele.isCalled()) {
                    Genotype possibleChild = new Genotype("possibleGenotype" + i, Arrays.asList(momAllele, dadAllele));
                    if (childG.sameGenotype(possibleChild, false)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }
}
