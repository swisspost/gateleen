package org.swisspush.gateleen.validation;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;


public class RegexpValidator {

    /**
     * <p>Tries to compile the passed regular expression pattern.</p>
     *
     * <p>This method will throw specified exception if passed pattern fails to compile.</p>
     *
     * @param pattern
     *      The pattern to validate.
     * @throws ValidationException
     *      Thrown in case there was a problem compiling passed pattern.
     */
    public static Pattern throwIfPatternInvalid( String pattern ) throws ValidationException {
        try{
            return Pattern.compile( pattern );
        }catch( PatternSyntaxException e ){
            throw new ValidationException( "Failed to parse regex pattern '"+pattern+"'." , e );
        }
    }

}
