package toberumono.wrf.timing.rounding;

import java.util.logging.Logger;

import toberumono.wrf.scope.ScopedMap;
import toberumono.wrf.timing.TimingComponent;

public abstract class Rounding extends TimingComponent<Rounding> {

	public Rounding(ScopedMap parameters, Rounding parent) {
		super(parameters, parent, Logger.getLogger("Rounding"));
	}
}
