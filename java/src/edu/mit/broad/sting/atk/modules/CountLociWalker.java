package edu.mit.broad.sting.atk.modules;

import edu.mit.broad.sting.atk.LocusIterator;
import edu.mit.broad.sting.utils.ReferenceOrderedDatum;

import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: mdepristo
 * Date: Feb 22, 2009
 * Time: 3:22:14 PM
 * To change this template use File | Settings | File Templates.
 */
public class CountLociWalker extends BasicLociWalker<Integer, Integer> {
    public Integer map(List<ReferenceOrderedDatum> rodData, char ref, LocusIterator context) {
        return 1;
    }

    public Integer reduceInit() { return 0; }

    public Integer reduce(Integer value, Integer sum) {
        return value + sum;
    }
}