
package edu.syr.pcpratts.rootbeer.testcases.rootbeertest.serialization;

import java.util.List;
import java.util.ArrayList;
import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class StringRunOnGPU implements Kernel {
  
  private String [] source; 
  private String [] ret; 
  private int index;
  
  public StringRunOnGPU (String [] src, String [] dst, int i)
  {
    source = src; 
    ret = dst; 
    index = i;
  }
  
  

  public void gpuMethod()
  {
    String Str= "york";
    for(int i = 0; i < source.length; ++i)
    {
      Str = source[i]+ Str;
    }
    ret[index] = Str;
  }
  
  public String[] getResult()
  {
    return ret;
  }
}
