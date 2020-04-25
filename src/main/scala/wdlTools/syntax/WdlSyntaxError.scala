package wdlTools.synx

class WdlSyntaxError extends RuntimeException {
  var errors = Vector.empty[SyntaxError]

  public WdlSyntaxError(String s, Throwable throwable) {
        super(s, throwable);
        errors = Collections.emptyList();
    }

    public WdlSyntaxError(SyntaxError syntaxError) {
        super();
        this.errors = Arrays.asList(syntaxError);
    }

    public WdlSyntaxError(List<SyntaxError> syntaxErrors) {
        super();
        this.errors = new ArrayList<>(syntaxErrors);
    }

    private String getErrorString() {
        return "\n" + String.join("\n", errors.stream().map(SyntaxError::toString).collect(Collectors.toList()));
    }

  def getErrors() : Vector[SyntaxError] = {
    return errors
  }

  def getMessage() : String = {
    if (errors != null && errors.size() > 0) {
      return getErrorString();
    } else {
      return super.getMessage();
    }
  }
}
