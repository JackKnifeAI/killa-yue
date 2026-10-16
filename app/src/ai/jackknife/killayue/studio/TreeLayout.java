package ai.jackknife.killayue.studio;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each step of the undo tree is drawn: x is how many steps from the start (a column), y is a lane. A step's
 * first branch carries on in its lane; every other branch takes the next free lane below, after the branches
 * before it, so no two steps share a place and lines only run right and down. Iterative, so a long history
 * never runs out of stack.
 */
public final class TreeLayout {
    public int[] x, y;
    public int lanes, columns, current;

    public static TreeLayout of(History h) {
        List<History.Step> steps = h.steps();
        int n = steps.size();
        TreeLayout t = new TreeLayout();
        t.x = new int[n];
        t.y = new int[n];
        t.current = h.currentStep();
        // children in the order they were made (steps are numbered in that order)
        int[] firstChild = new int[n], nextSibling = new int[n], lastChild = new int[n];
        java.util.Arrays.fill(firstChild, -1);
        java.util.Arrays.fill(nextSibling, -1);
        java.util.Arrays.fill(lastChild, -1);
        for (History.Step s : steps) {
            if (s.parent < 0) continue;
            if (firstChild[s.parent] < 0) firstChild[s.parent] = s.id;
            else nextSibling[lastChild[s.parent]] = s.id;
            lastChild[s.parent] = s.id;
        }
        int nextLane = 0, maxX = 0;
        // depth first: a step's whole first branch before its second, each new branch on a fresh lane
        ArrayList<Integer> stack = new ArrayList<Integer>();
        stack.add(0);
        t.x[0] = 0;
        t.y[0] = 0;
        while (!stack.isEmpty()) {
            int s = stack.remove(stack.size() - 1);
            if (t.y[s] < 0) t.y[s] = ++nextLane;  // a branch reached: its lane, before its children take it
            maxX = Math.max(maxX, t.x[s]);
            // push the children so the first comes off the stack first; lanes for the others are given as
            // they come off, after the earlier branches have taken theirs
            ArrayList<Integer> kids = new ArrayList<Integer>();
            for (int c = firstChild[s]; c >= 0; c = nextSibling[c]) kids.add(c);
            for (int i = kids.size() - 1; i >= 0; i--) {
                int c = kids.get(i);
                t.x[c] = t.x[s] + 1;
                t.y[c] = i == 0 ? t.y[s] : -1;  // -1: a new lane, given when it is reached
                stack.add(c);
            }
        }
        t.lanes = nextLane + 1;
        t.columns = maxX + 1;
        return t;
    }
}
