package toberumono.wrf.scope;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import toberumono.utils.functions.ExceptedSupplier;

public class AbstractScope<T extends Scope> implements Scope {
	private final T parent;
	private final Map<String, ExceptedSupplier<Object>> namedItems;
	
	public AbstractScope(T parent) {
		this.parent = parent;
		namedItems = new HashMap<>();
		for (Field f : getClass().getDeclaredFields()) {
			NamedScopeValue nsv = f.getAnnotation(NamedScopeValue.class);
			if (nsv != null) {
				f.setAccessible(true);
				namedItems.put(nsv.value(), () -> f.get(this));
			}
		}
		for (Method m : getClass().getDeclaredMethods()) {
			NamedScopeValue nsv = m.getAnnotation(NamedScopeValue.class);
			if (nsv != null) {
				m.setAccessible(true);
				namedItems.put(nsv.value(), () -> m.invoke(this));
			}
		}
	}
	
	@Override
	public T getParent() {
		return parent;
	}
	
	@Override
	public boolean hasValueByName(String name) {
		return namedItems.containsKey(name);
	}
	
	@Override
	public Object getValueByName(String name) throws InvalidVariableAccessException {
		try {
			if (namedItems.containsKey(name))
				return namedItems.get(name).get();
			else
				throw new InvalidVariableAccessException("'" + name + "' does not exist in the current scope.");
		}
		catch (Throwable t) {
			throw new InvalidVariableAccessException("Could not access '" + name + "'.", t);
		}
	}
}